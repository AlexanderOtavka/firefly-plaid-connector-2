# The OCI image, a drop-in for the Paketo bootBuildImage image: the app is the entrypoint
# (no command or args needed), as uid 1000, fine with a read-only root filesystem and a
# writable /tmp mounted by the runtime. Spring config, credentials, and the cursor directory
# are all mounted by the manifests, and JAVA_TOOL_OPTIONS from them reaches the JVM as is.
{
  dockerTools,
  cacert,
  firefly-plaid-connector-2,
}:
dockerTools.buildLayeredImage {
  name = "firefly-plaid-connector-2";
  tag = "latest";
  contents = [cacert];
  # /tmp for Tomcat and the JVM where the runtime mounts nothing there (the polled connector
  # and backfill Jobs run with a writable root). uid 1000 gets a passwd entry so the JVM's
  # user.name and user.home resolve; its home is /tmp, the one writable place.
  extraCommands = ''
    mkdir -m 1777 tmp
    mkdir -p etc
    echo 'root:x:0:0:root:/root:/sbin/nologin' > etc/passwd
    echo 'connector:x:1000:1000:connector:/tmp:/sbin/nologin' >> etc/passwd
    echo 'root:x:0:' > etc/group
    echo 'connector:x:1000:' >> etc/group
  '';
  config = {
    Entrypoint = ["${firefly-plaid-connector-2}/bin/firefly-plaid-connector-2"];
    User = "1000:1000";
    ExposedPorts."8080/tcp" = {};
    Env = [
      "HOME=/tmp"
      "SSL_CERT_FILE=${cacert}/etc/ssl/certs/ca-bundle.crt"
    ];
    Labels = {
      "org.opencontainers.image.source" = "https://github.com/AlexanderOtavka/firefly-plaid-connector-2";
      "org.opencontainers.image.description" = firefly-plaid-connector-2.meta.description;
      "org.opencontainers.image.licenses" = "GPL-3.0-only";
      "org.opencontainers.image.version" = firefly-plaid-connector-2.version;
    };
  };
}
