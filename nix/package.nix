{
  lib,
  stdenv,
  gradle_8,
  jdk17_headless,
  jre17_minimal,
  makeWrapper,
}: let
  # build.gradle.kts targets Java 17 (sourceCompatibility and jvmTarget), which is also what
  # the Paketo image this replaces ran on. Gradle 8, because the Kotlin 1.9 Gradle plugin
  # predates Gradle 9.
  jdk = jdk17_headless;
  gradle = gradle_8.override {java = jdk;};

  # A JRE rather than the whole JDK: every runtime module, without the compiler, debugger,
  # and other development tools, or the desktop libraries a non-headless JDK pulls in.
  jre = jre17_minimal.override {
    inherit jdk;
    jdkOnBuild = jdk;
    modules = [
      "java.se"
      "jdk.charsets"
      "jdk.crypto.cryptoki"
      "jdk.crypto.ec"
      "jdk.dynalink"
      "jdk.httpserver"
      "jdk.jfr"
      "jdk.localedata"
      "jdk.management"
      "jdk.management.agent"
      "jdk.management.jfr"
      "jdk.naming.dns"
      "jdk.naming.rmi"
      "jdk.net"
      "jdk.nio.mapmode"
      "jdk.random"
      "jdk.sctp"
      "jdk.security.auth"
      "jdk.security.jgss"
      "jdk.unsupported"
      "jdk.xml.dom"
      "jdk.zipfs"
    ];
  };

  src = lib.fileset.toSource {
    root = ../.;
    fileset = lib.fileset.unions [
      ../build.gradle.kts
      ../settings.gradle.kts
      ../gradle.properties
      ../gradle/libs.versions.toml
      ../specs
      ../src
    ];
  };

  # The one version, kept in build.gradle.kts.
  version = builtins.head (builtins.match ''.*[^.]version = "([^"]+)".*'' (builtins.readFile ../build.gradle.kts));
in
  stdenv.mkDerivation (finalAttrs: {
    pname = "firefly-plaid-connector-2";
    inherit version src;

    nativeBuildInputs = [gradle makeWrapper];

    # Every Maven artifact the build fetches, pinned by hash in deps.json. Regenerate it after
    # changing dependencies in build.gradle.kts, or bumping nixpkgs' Gradle:
    #   nix build .#firefly-plaid-connector-2.mitmCache.updateScript && ./result
    mitmCache = gradle.fetchDeps {
      pkg = finalAttrs.finalPackage;
      data = ./deps.json;
    };
    __darwinAllowLocalNetworking = true;

    gradleFlags = ["-Dfile.encoding=utf-8"];
    gradleBuildTask = "bootJar";
    # nixDownloadDeps resolves every configuration, but some artifacts (Kotlin compiler
    # plugins, the test launcher) are only fetched while tasks run, so run those too.
    gradleUpdateTask = "nixDownloadDeps bootJar testClasses";

    installPhase = ''
      runHook preInstall
      install -Dm444 build/libs/fireflyPlaidConnector2-${finalAttrs.version}.jar \
        $out/share/firefly-plaid-connector-2/firefly-plaid-connector-2.jar
      # ExitOnOutOfMemoryError, as Paketo's JVM launcher sets: let the pod restart rather
      # than limp on. Flags here come after JAVA_TOOL_OPTIONS, so heap sizing stays there.
      makeWrapper ${jre}/bin/java $out/bin/firefly-plaid-connector-2 \
        --add-flags "-XX:+ExitOnOutOfMemoryError" \
        --add-flags "-jar $out/share/firefly-plaid-connector-2/firefly-plaid-connector-2.jar"
      runHook postInstall
    '';

    passthru = {inherit gradle jdk jre;};

    meta = {
      description = "Syncs Plaid transactions into Firefly III, with a dashboard for managing bank links";
      homepage = "https://github.com/AlexanderOtavka/firefly-plaid-connector-2";
      license = lib.licenses.gpl3Only;
      mainProgram = "firefly-plaid-connector-2";
      sourceProvenance = with lib.sourceTypes; [
        fromSource
        binaryBytecode # Maven dependencies, from the mitm cache
      ];
    };
  })
