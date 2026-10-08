{
  testers,
  firefly-plaid-connector-2,
}: let
  # The compiled tests and their runtime classpath (the vmCheckDist Gradle task), built with
  # the package's dependencies.
  dist = firefly-plaid-connector-2.overrideAttrs {
    pname = "firefly-plaid-connector-2-vm-check";
    gradleBuildTask = "vmCheckDist";
    installPhase = ''
      runHook preInstall
      cp -r build/vm-check $out
      runHook postInstall
    '';
  };
  inherit (firefly-plaid-connector-2.passthru) jre;
in {
  # Creates asset accounts through the connector's Firefly client against a real Firefly III,
  # as the dashboard's mapping page does (FireflyVmCheck.kt).
  firefly-accounts = testers.runNixOSTest {
    name = "firefly-plaid-connector-2-firefly-accounts";

    nodes.machine = {config, ...}: let
      firefly = config.services.firefly-iii.package;
      # Firefly has no command for a personal access token, so make one as the API would.
      mkToken = ''
        <?php
        require '${firefly}/vendor/autoload.php';
        $app = require '${firefly}/bootstrap/app.php';
        $app->make(Illuminate\Contracts\Console\Kernel::class)->bootstrap();
        Illuminate\Support\Facades\Artisan::call('passport:client', [
            '--personal' => true, '--name' => 'vm-check', '--provider' => 'users', '--no-interaction' => true,
        ]);
        echo FireflyIII\User::firstOrFail()->createToken('vm-check')->accessToken;
      '';
    in {
      virtualisation.memorySize = 2048;
      environment.etc."firefly-iii-appkey".text = "TestTestTestTestTestTestTestTest";
      services.firefly-iii = {
        enable = true;
        enableNginx = true;
        settings = {
          APP_KEY_FILE = "/etc/firefly-iii-appkey";
          LOG_CHANNEL = "stdout";
          SITE_OWNER = "mail@example.com";
        };
      };
      environment.systemPackages = [firefly.phpPackage];
      environment.etc."vm-check/mk-token.php".text = mkToken;
    };

    testScript = ''
      machine.wait_for_unit("phpfpm-firefly-iii.service")
      machine.wait_for_unit("nginx.service")

      # Register the first user, who becomes the owner, through the web form.
      machine.succeed(
          "curl -fsS -c /tmp/jar -b /tmp/jar -o /tmp/register.html http://localhost/register",
          "grep -oP 'name=\"_token\" value=\"\\K[^\"]+' /tmp/register.html | tr -d '\\n' > /tmp/csrf",
      )
      redirect = machine.succeed(
          "curl -fsS -c /tmp/jar -b /tmp/jar -o /dev/null -w '%{redirect_url}' http://localhost/register"
          + " --data-urlencode _token@/tmp/csrf"
          + " --data-urlencode email=test@example.com"
          + " --data-urlencode password=TestTestTestTest1"
          + " --data-urlencode password_confirmation=TestTestTestTest1"
      )
      assert "register" not in redirect, f"registration failed, redirected to {redirect}"

      machine.succeed(
          "runuser -u firefly-iii -- php /etc/vm-check/mk-token.php 2>/dev/null | tail -n1 > /tmp/pat",
          "test -s /tmp/pat",
      )

      status, output = machine.execute(
          "FIREFLY_URL=http://localhost FIREFLY_TOKEN_FILE=/tmp/pat"
          + " ${jre}/bin/java -cp '${dist}/classes/main:${dist}/classes/test:${dist}/lib/*'"
          + " net.djvk.fireflyPlaidConnector2.manage.firefly.FireflyVmCheckKt 2>&1"
      )
      print(output)
      assert status == 0, "FireflyVmCheck failed"
    '';
  };
}
