{
  lib,
  stdenv,
  callPackage,
  gradle-packages,
  jdk17_headless,
  jre17_minimal,
  makeWrapper,
}: let
  # build.gradle.kts targets Java 17 (sourceCompatibility and jvmTarget), which is also what
  # the Paketo image this replaces ran on.
  jdk = jdk17_headless;

  # The Gradle the wrapper names, checked against the wrapper's distributionSha256Sum, so a
  # Nix build and ./gradlew (which Renovate runs to record dependencies) agree. Renovate
  # bumps both lines together.
  wrapperProperties = builtins.readFile ../gradle/wrapper/gradle-wrapper.properties;
  gradleVersion = builtins.head (builtins.match
    ''.*distributionUrl=https\\://services\.gradle\.org/distributions/gradle-([^-]+)-bin\.zip.*''
    wrapperProperties);
  gradleSha256 = builtins.head (builtins.match ''.*distributionSha256Sum=([0-9a-f]{64}).*'' wrapperProperties);
  gradle =
    (gradle-packages.mkGradle {
      version = gradleVersion;
      hash = builtins.convertHash {
        hash = gradleSha256;
        hashAlgo = "sha256";
        toHashFormat = "sri";
      };
      defaultJava = jdk;
    }).wrapped;

  mavenRepo = callPackage ./maven-repo.nix {
    verificationMetadata = ../gradle/verification-metadata.xml;
  };

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
      # Gradle checks every artifact against it, in the Nix build too.
      ../gradle/verification-metadata.xml
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

    # Every Maven artifact the build needs, from gradle/verification-metadata.xml. The gradle
    # setup hook runs Gradle --offline, and this init script points it at the local repo.
    env.NIX_MAVEN_REPO = mavenRepo;

    gradleFlags = ["-Dfile.encoding=utf-8" "--init-script" "${./offline-repo.gradle}"];
    gradleBuildTask = "bootJar";

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

    passthru = {inherit gradle jdk jre mavenRepo;};

    meta = {
      description = "Syncs Plaid transactions into Firefly III, with a dashboard for managing bank links";
      homepage = "https://github.com/AlexanderOtavka/firefly-plaid-connector-2";
      license = lib.licenses.gpl3Only;
      mainProgram = "firefly-plaid-connector-2";
      sourceProvenance = with lib.sourceTypes; [
        fromSource
        binaryBytecode # Maven dependencies, from gradle/verification-metadata.xml
      ];
    };
  })
