{
  postgresql_17,
  firefly-plaid-connector-2,
}: {
  # The Gradle test suite. Reuses the package's dependencies and build; only the check and
  # output differ. src/manageTest needs a PostgreSQL, and zonky's embedded binaries do not
  # run on NixOS, so this starts a throwaway one and points the tests at it
  # (PLAID_MANAGER_TEST_PG_PORT, see TestPostgres.kt).
  tests = firefly-plaid-connector-2.overrideAttrs (old: {
    pname = "firefly-plaid-connector-2-tests";
    nativeBuildInputs = old.nativeBuildInputs ++ [postgresql_17];
    gradleBuildTask = "testClasses";
    doCheck = true;

    preCheck = ''
      export PGDATA=$TMPDIR/pgdata PGHOST=$TMPDIR/pgsocket
      mkdir -p $PGHOST
      initdb -U postgres --auth=trust --no-sync -E UTF8 >/dev/null
      # The sandbox has its own network namespace, but outside one (Darwin, --option sandbox
      # false) the port may be taken, so try a few.
      for port in $(seq 55432 55482); do
        if pg_ctl -w -l $TMPDIR/postgres.log \
          -o "-k $PGHOST -c listen_addresses=localhost -p $port -c fsync=off" start >/dev/null; then
          export PLAID_MANAGER_TEST_PG_PORT=$port
          break
        fi
      done
      if [ -z "''${PLAID_MANAGER_TEST_PG_PORT:-}" ]; then
        cat $TMPDIR/postgres.log
        exit 1
      fi
    '';

    # Fail on skipped tests, or on no tests at all: either would let the check pass while
    # testing nothing.
    postCheck = ''
      pg_ctl -w stop -m fast >/dev/null
      results=(build/test-results/test/*.xml)
      [ -e "''${results[0]}" ] || { echo "no test results" >&2; exit 1; }
      tests=0 skipped=0
      for f in "''${results[@]}"; do
        read -r t s < <(sed -n 's/.*<testsuite [^>]*tests="\([0-9]*\)"[^>]*skipped="\([0-9]*\)".*/\1 \2/p' "$f" | head -1)
        tests=$((tests + t)) skipped=$((skipped + s))
      done
      echo "$tests tests, $skipped skipped, in ''${#results[@]} classes"
      [ "$tests" -gt 0 ] && [ "$skipped" -eq 0 ]
    '';

    installPhase = ''
      runHook preInstall
      mkdir -p $out
      cp -r build/test-results/test $out/test-results
      runHook postInstall
    '';
  });
}
