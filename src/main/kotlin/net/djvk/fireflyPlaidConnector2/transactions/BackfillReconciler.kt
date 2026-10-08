package net.djvk.fireflyPlaidConnector2.transactions

import net.djvk.fireflyPlaidConnector2.api.firefly.apis.FireflyTransactionId
import net.djvk.fireflyPlaidConnector2.api.firefly.models.TransactionRead
import net.djvk.fireflyPlaidConnector2.api.firefly.models.TransactionSplit
import net.djvk.fireflyPlaidConnector2.api.firefly.models.TransactionTypeProperty
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs

/** Prefix of every `external_id` the connector writes; see [FireflyTransactionExternalIdIndexer]. */
const val PLAID_EXTERNAL_ID_PREFIX = "plaid-"

enum class ReviewKind {
    /** A converted transaction that could be one of several imports; nothing was written for it. */
    UNMATCHED,

    /** An import nothing in this run matched, beside one that did: probably a duplicate. */
    LEFTOVER,
}

/** An existing Firefly transaction, with the `external_id` it had when the review was made. */
data class ReviewTarget(val id: FireflyTransactionId, val externalId: String?)

/** Something a backfill could not decide safely, for the owner to resolve on the dashboard. */
data class ReviewCandidate(
    val kind: ReviewKind,
    val reason: String,
    val date: LocalDate,
    val amount: String,
    val description: String,
    /** [ReviewKind.UNMATCHED]: the transaction the run would have inserted, with a null id. */
    val proposed: FireflyTransactionDto?,
    /**
     * [ReviewKind.UNMATCHED]: imports it could be. [ReviewKind.LEFTOVER]: the possible
     * duplicate itself.
     */
    val targets: List<ReviewTarget>,
    /** [ReviewKind.LEFTOVER]: imports this run matched that it may duplicate. */
    val relatedIds: List<FireflyTransactionId> = emptyList(),
    /** Text the connector could have written for [proposed]; see [TransactionConverter.refreshImported]. */
    val generated: Set<String> = emptySet(),
)

data class ReconcilePlan(
    /** Converted transactions with no existing counterpart, to insert as before. */
    val inserts: List<FireflyTransactionDto>,
    /** Existing transactions to update in place; every one has an id. */
    val updates: List<FireflyTransactionDto>,
    /** Converted transactions that matched an existing one, whether or not it needs an update. */
    val matched: Int,
    val reviews: List<ReviewCandidate>,
)

/**
 * Local change: matches a batch run's converted transactions against what the connector
 * has already imported, so a re-import or a relink updates those transactions in place instead
 * of duplicating them.
 *
 * Firefly's duplicate hash covers the whole transaction body, so it misses a re-import once the
 * connector writes a transaction differently, and a relinked Item gives every transaction a new
 * Plaid `transaction_id` and so a new `external_id`. This looks at every connector import (an
 * `external_id` starting with `plaid-`) in the accounts being backfilled instead:
 *
 * 1. **Exact:** an existing transaction with the same `external_id`.
 * 2. **Fuzzy:** otherwise, an existing one in the same Firefly account, in the same direction,
 *    for the same amount, within [dateWindowDays]. Each side of a transfer is matched on its own
 *    account. Candidates are paired one to one, so two identical charges on a day match two
 *    existing ones: same date and payee first, then a lone pair with the same payee within the
 *    window, then a lone pair on a date, then a lone pair within the window. Anything else is
 *    ambiguous and goes to review rather than being guessed.
 *
 * A match is updated in place through [refresh], which takes the new `external_id` and the
 * connector's fields and keeps the owner's. Firefly cannot change a transaction's type, so a
 * match whose type differs (a single charge that an earlier run had paired into a transfer, or
 * the reverse) is left as it is: the money movement is already recorded.
 *
 * Finally, an import that nothing matched, with a matched transaction of the same account,
 * direction, and amount within the window, is listed for review as a probable duplicate. That
 * is what a charge left pending on a replaced Item looks like once the new Item's posted copy
 * has been imported.
 */
class BackfillReconciler(
    private val zoneId: ZoneId,
    private val dateWindowDays: Long,
    private val refresh: (converted: TransactionSplit, existing: TransactionSplit, generated: Set<String>) -> TransactionSplit,
) {
    private val logger = LoggerFactory.getLogger(this::class.java)

    private enum class Side { OUT, IN }

    /** One account's side of a transaction: where it matches. */
    private data class LegKey(val account: String, val side: Side, val amount: String)

    private class Leg(val owner: Int, val key: LegKey, val date: LocalDate, val payee: String)

    private class Existing(val tx: TransactionRead, val split: TransactionSplit) {
        val target get() = ReviewTarget(tx.id, split.externalId)
    }

    /**
     * @param converted This run's converted transactions, none of them persisted.
     * @param existing Firefly transactions in the run's accounts and date range (plus the window).
     *  Only single-split connector imports are considered.
     * @param accounts The Firefly accounts this run imports into. Sides in other accounts are
     *  not compared, because the run did not fetch their transactions.
     * @param generated Per `external_id`, the text the connector could have written for it.
     */
    fun plan(
        converted: List<FireflyTransactionDto>,
        existing: List<TransactionRead>,
        accounts: Set<FireflyAccountId>,
        generated: Map<String, Set<String>> = emptyMap(),
    ): ReconcilePlan {
        val inScope = accounts.map { it.toString() }.toSet()
        val olds = existing
            .distinctBy { it.id }
            .mapNotNull { tx ->
                val split = tx.attributes.transactions.singleOrNull() ?: return@mapNotNull null
                if (split.externalId?.startsWith(PLAID_EXTERNAL_ID_PREFIX) != true) return@mapNotNull null
                Existing(tx, split)
            }
        fun generatedFor(tx: FireflyTransactionDto) = tx.tx.externalId?.let { generated[it] }.orEmpty()

        // Old index matched to each converted transaction, by leg.
        val matchedTo = HashMap<Int, MutableMap<LegKey, Int>>()
        val ambiguous = HashMap<Int, MutableSet<Int>>()
        val consumed = HashSet<Int>()

        // 1. Exact external_id.
        val oldByExternalId = olds.withIndex().associate { (i, old) -> old.split.externalId!! to i }
        val exact = HashMap<Int, Int>()
        for ((n, tx) in converted.withIndex()) {
            val o = tx.tx.externalId?.let { oldByExternalId[it] } ?: continue
            if (consumed.add(o)) exact[n] = o
        }

        // 2. Fuzzy, by leg.
        val newLegs = converted.withIndex()
            .filter { (n, _) -> n !in exact }
            .flatMap { (n, tx) -> legsOf(n, tx.tx, inScope) }
        val oldLegs = olds.withIndex()
            .filter { (o, _) -> o !in consumed }
            .flatMap { (o, old) -> legsOf(o, old.split, inScope) }
        val oldsByKey = oldLegs.groupBy { it.key }
        for ((key, news) in newLegs.groupBy { it.key }) {
            pairGroup(news, oldsByKey[key].orEmpty(), matchedTo, ambiguous)
        }

        val inserts = mutableListOf<FireflyTransactionDto>()
        val updates = mutableListOf<FireflyTransactionDto>()
        val reviews = mutableListOf<ReviewCandidate>()
        val matchedNews = HashMap<Int, Set<Int>>()

        fun review(tx: FireflyTransactionDto, reason: String, targets: Collection<Int>) {
            reviews += ReviewCandidate(
                kind = ReviewKind.UNMATCHED,
                reason = reason,
                date = tx.tx.date.atZoneSameInstant(zoneId).toLocalDate(),
                amount = tx.tx.amount,
                description = tx.tx.description,
                proposed = tx,
                targets = targets.distinct().map { olds[it].target },
                generated = generatedFor(tx),
            )
        }

        for ((n, tx) in converted.withIndex()) {
            val exactOld = exact[n]
            if (exactOld != null) {
                matchedNews[n] = setOf(exactOld)
                updateFor(tx, olds[exactOld], generatedFor(tx))?.let { updates += it }
                continue
            }
            val legs = legsOf(n, tx.tx, inScope)
            val candidates = ambiguous[n]
            if (candidates != null) {
                review(tx, "Several imported transactions could be this one", candidates)
                continue
            }
            val hits = matchedTo[n].orEmpty()
            when {
                hits.isEmpty() -> inserts += tx
                hits.size < legs.size -> review(
                    tx,
                    "One side of this transfer is already imported as a separate transaction. " +
                        "Dismiss to keep it as it is, or delete it in Firefly and import the transfer",
                    hits.values,
                )
                else -> {
                    val targets = hits.values.toSet()
                    matchedNews[n] = targets
                    val target = targets.singleOrNull()
                    if (target != null) {
                        updateFor(tx, olds[target], generatedFor(tx))?.let { updates += it }
                    } else {
                        // A transfer whose sides were imported as two separate transactions.
                        logger.info(
                            "Leaving Firefly transactions {} as they are for {}: already imported separately",
                            targets.map { olds[it].tx.id },
                            tx.tx.externalId,
                        )
                    }
                }
            }
        }

        reviews += leftovers(converted, olds, inScope, matchedNews, consumed, ambiguous)

        logger.info(
            "Reconciled {} converted transactions against {} imported ones: {} matched ({} to update), {} new, {} for review",
            converted.size,
            olds.size,
            matchedNews.size,
            updates.size,
            inserts.size,
            reviews.size,
        )
        return ReconcilePlan(inserts, updates, matchedNews.size, reviews)
    }

    /**
     * Imports nothing matched, beside a matched one with the same account, direction, and
     * amount within the window. Nothing is deleted here; the owner decides.
     */
    private fun leftovers(
        converted: List<FireflyTransactionDto>,
        olds: List<Existing>,
        inScope: Set<String>,
        matchedNews: Map<Int, Set<Int>>,
        consumed: Set<Int>,
        ambiguous: Map<Int, Set<Int>>,
    ): List<ReviewCandidate> {
        val used = consumed + matchedNews.values.flatten() + ambiguous.values.flatten()
        // Imports fetched only for the window's padding have no counterpart in this run.
        val dates = converted.map { it.tx.date.atZoneSameInstant(zoneId).toLocalDate() }
        val first = dates.minOrNull() ?: return emptyList()
        val last = dates.max()
        val matchedLegs = matchedNews.keys.flatMap { n -> legsOf(n, converted[n].tx, inScope) }.groupBy { it.key }
        return olds.withIndex()
            .filter { (o, old) -> o !in used && old.split.date.atZoneSameInstant(zoneId).toLocalDate() in first..last }
            .mapNotNull { (o, old) ->
                val near = legsOf(o, old.split, inScope).flatMap { leg ->
                    matchedLegs[leg.key].orEmpty().filter { abs(it.date.toEpochDay() - leg.date.toEpochDay()) <= dateWindowDays }
                }
                if (near.isEmpty()) return@mapNotNull null
                ReviewCandidate(
                    kind = ReviewKind.LEFTOVER,
                    reason = "Nothing from the bank matches this import, but a matching transaction is " +
                        "already in Firefly; it is probably a duplicate, e.g. a charge that was still " +
                        "pending when the bank was relinked",
                    date = old.split.date.atZoneSameInstant(zoneId).toLocalDate(),
                    amount = old.split.amount,
                    description = old.split.description,
                    proposed = null,
                    targets = listOf(old.target),
                    relatedIds = near.flatMap { matchedNews[it.owner].orEmpty() }.distinct().map { olds[it].tx.id },
                )
            }
    }

    /** The update for [old], or null when it is a different type or already up to date. */
    private fun updateFor(converted: FireflyTransactionDto, old: Existing, generated: Set<String>): FireflyTransactionDto? {
        if (converted.tx.type != old.split.type) {
            logger.info(
                "Leaving Firefly transaction {} ({}) as it is for {} ({}): Firefly cannot change a transaction's type",
                old.tx.id,
                old.split.type,
                converted.tx.externalId,
                converted.tx.type,
            )
            return null
        }
        val updated = refresh(converted.tx, old.split, generated)
        if (!changed(updated, old.split)) return null
        return FireflyTransactionDto(old.tx.id, updated, applyRules = false)
    }

    /** Whether writing [updated] would change anything the connector manages. */
    private fun changed(updated: TransactionSplit, old: TransactionSplit): Boolean =
        updated.externalId != old.externalId ||
            updated.description != old.description ||
            updated.categoryName != old.categoryName ||
            updated.date.toInstant() != old.date.toInstant() ||
            updated.externalUrl != old.externalUrl ||
            updated.tags.orEmpty().toSet() != old.tags.orEmpty().toSet() ||
            payeeName(updated)?.let { it != payeeName(old) } == true

    private fun payeeName(split: TransactionSplit): String? = when (split.type) {
        TransactionTypeProperty.withdrawal -> split.destinationName
        TransactionTypeProperty.deposit -> split.sourceName
        else -> null
    }

    private fun legsOf(owner: Int, split: TransactionSplit, inScope: Set<String>): List<Leg> {
        val amount = try {
            BigDecimal(split.amount).stripTrailingZeros().toPlainString()
        } catch (_: NumberFormatException) {
            return emptyList()
        }
        val date = split.date.atZoneSameInstant(zoneId).toLocalDate()
        fun leg(account: String?, side: Side, payee: String?) =
            account?.takeIf { it in inScope }?.let {
                Leg(owner, LegKey(it, side, amount), date, normalize(payee))
            }
        return when (split.type) {
            TransactionTypeProperty.withdrawal -> listOfNotNull(leg(split.sourceId, Side.OUT, split.destinationName))
            TransactionTypeProperty.deposit -> listOfNotNull(leg(split.destinationId, Side.IN, split.sourceName))
            TransactionTypeProperty.transfer -> listOfNotNull(
                leg(split.sourceId, Side.OUT, "account ${split.destinationId}"),
                leg(split.destinationId, Side.IN, "account ${split.sourceId}"),
            )
            else -> emptyList()
        }
    }

    private fun normalize(name: String?): String =
        name?.let { TransactionConverter.normalizeForRedundancyCheck(it) }.orEmpty()

    /** Pairs one group of legs that share an account, side, and amount. */
    private fun pairGroup(
        news: List<Leg>,
        olds: List<Leg>,
        matchedTo: MutableMap<Int, MutableMap<LegKey, Int>>,
        ambiguous: MutableMap<Int, MutableSet<Int>>,
    ) {
        val freeNew = news.sortedBy { it.date }.toMutableList()
        val freeOld = olds.sortedBy { it.date }.toMutableList()
        fun pair(n: Leg, o: Leg) {
            matchedTo.getOrPut(n.owner) { HashMap() }[n.key] = o.owner
            freeNew.remove(n)
            freeOld.remove(o)
        }

        // Same date and payee: interchangeable, so pair in order.
        for (n in freeNew.toList()) {
            freeOld.firstOrNull { it.date == n.date && it.payee == n.payee }?.let { pair(n, it) }
        }
        // The same payee on a nearby date, e.g. a pending charge that posted later.
        for (payee in freeNew.map { it.payee }.distinct()) {
            val samePayee = { leg: Leg -> leg.payee == payee }
            for (component in components(freeNew.filter(samePayee), freeOld.filter(samePayee))) {
                val (cn, co) = component.partition { it in freeNew }
                if (cn.size == 1 && co.size == 1) pair(cn.single(), co.single())
            }
        }
        // A lone pair on a date, e.g. after the payee name changed.
        for (date in freeNew.map { it.date }.distinct()) {
            val n = freeNew.singleOrNull { it.date == date } ?: continue
            val o = freeOld.singleOrNull { it.date == date } ?: continue
            pair(n, o)
        }
        // Within the window: only a lone pair is safe; the rest go to review.
        for (component in components(freeNew, freeOld)) {
            val (cn, co) = component.partition { it in freeNew }
            when {
                co.isEmpty() -> Unit
                cn.size == 1 && co.size == 1 -> pair(cn.single(), co.single())
                else -> for (n in cn) {
                    ambiguous.getOrPut(n.owner) { HashSet() }.addAll(co.map { it.owner })
                }
            }
        }
    }

    /** Groups legs connected by new-to-old edges within the date window. */
    private fun components(news: List<Leg>, olds: List<Leg>): List<List<Leg>> {
        val all = news + olds
        val parent = IntArray(all.size) { it }
        fun find(i: Int): Int {
            var x = i
            while (parent[x] != x) {
                parent[x] = parent[parent[x]]
                x = parent[x]
            }
            return x
        }
        for (i in news.indices) {
            for (j in olds.indices) {
                val days = abs(news[i].date.toEpochDay() - olds[j].date.toEpochDay())
                if (days <= dateWindowDays) parent[find(i)] = find(news.size + j)
            }
        }
        return all.indices.groupBy { find(it) }.values.map { members -> members.map { all[it] } }
    }
}
