package net.djvk.fireflyPlaidConnector2.manage

import kotlinx.coroutines.runBlocking
import net.djvk.fireflyPlaidConnector2.manage.db.DATABASE_ITEM_STORE
import net.djvk.fireflyPlaidConnector2.sync.ITEM_STORE_PROPERTY
import net.djvk.fireflyPlaidConnector2.sync.Runner
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * The `manage` sync mode: the dashboard. The servlet container keeps the process alive, so the
 * runner itself only checks the mode is usable.
 */
@Component
@ConditionalOnProperty(name = [SYNC_MODE_PROPERTY], havingValue = MANAGE_MODE)
class ManageRunner(
    @Value("\${$ITEM_STORE_PROPERTY:config}")
    private val itemStore: String,
) : Runner {
    private val logger = LoggerFactory.getLogger(this::class.java)

    init {
        require(itemStore == DATABASE_ITEM_STORE) { "syncMode: manage needs itemStore: database" }
    }

    override fun run() {
        logger.info("Plaid management dashboard is up")
    }
}

/** Background work in manage mode: Items waiting on their history, and backfill Jobs. */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = [SYNC_MODE_PROPERTY], havingValue = MANAGE_MODE)
class ManageSchedule(
    private val itemService: ItemService,
    private val backfills: BackfillService,
) {
    private val logger = LoggerFactory.getLogger(this::class.java)

    @Scheduled(initialDelayString = "PT30S", fixedDelayString = "PT60S")
    fun tick() {
        try {
            runBlocking { itemService.advancePendingItems() }
        } catch (e: Exception) {
            logger.warn("Checking pending Items failed: {}", e.javaClass.simpleName)
        }
        try {
            backfills.reconcile()
        } catch (e: Exception) {
            logger.warn("Reconciling backfills failed: {}", e.javaClass.simpleName)
        }
    }
}
