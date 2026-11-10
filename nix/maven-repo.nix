# Every Maven artifact the Gradle build needs, as a local Maven repository, built from the
# sha256s Gradle records in gradle/verification-metadata.xml. Renovate rewrites that file
# (./gradlew --write-verification-metadata sha256 dependencies) with every dependency it
# bumps, so nothing here needs regenerating by hand.
{
  lib,
  fetchurl,
  linkFarm,
  verificationMetadata,
}: let
  xml = builtins.readFile verificationMetadata;

  # The file only records each artifact's coordinates, not the repository it came from, so
  # try the ones the build uses. The hash pins the content either way. The plugin portal
  # first: plugins resolve from it, and some publish a different jar to Maven Central
  # (openapi-generator-gradle-plugin does), while it serves everything else from Central.
  repositories = [
    "https://plugins.gradle.org/m2"
    "https://repo.maven.apache.org/maven2"
  ];

  # builtins.split interleaves the text between matches with each match's captures: here,
  # every <component ...> header's [group name version], followed by its body.
  pieces = builtins.split ''<component group="([^"]+)" name="([^"]+)" version="([^"]+)">'' xml;
  components = let
    go = i:
      if i + 1 >= builtins.length pieces
      then []
      else let
        header = builtins.elemAt pieces i;
      in
        if builtins.isList header
        then [
          {
            group = builtins.elemAt header 0;
            name = builtins.elemAt header 1;
            version = builtins.elemAt header 2;
            body = builtins.elemAt pieces (i + 1);
          }
        ]
        ++ go (i + 2)
        else go (i + 1);
  in
    go 0;

  artifactsOf = c:
    map (m: {
      inherit (c) group name version;
      file = builtins.elemAt m 0;
      sha256 = builtins.elemAt m 1;
    }) (builtins.filter builtins.isList (builtins.split
      ''<artifact name="([^"]+)">[[:space:]]*<sha256 value="([0-9a-f]{64})"''
      c.body));

  artifacts = lib.concatMap artifactsOf components;

  # Gradle records each file under its name in the variant's .module, which isn't always the
  # file's name in the repository. Kotlin Multiplatform libraries name their metadata jar
  # foo-metadata-1.0.jar but publish it as foo-1.0.jar. Fetch whichever exists, and serve
  # it under both names: the hash says which file it is.
  fileNames = a: let
    prefix = "${a.name}-metadata-";
  in
    [a.file]
    ++ lib.optional (lib.hasPrefix prefix a.file)
    "${a.name}-${lib.removePrefix prefix a.file}";

  entries = a: let
    dir = "${lib.replaceStrings ["."] ["/"] a.group}/${a.name}/${a.version}";
    names = fileNames a;
    file = fetchurl {
      name = lib.strings.sanitizeDerivationName a.file;
      urls = lib.concatMap (r: map (n: "${r}/${dir}/${n}") names) repositories;
      inherit (a) sha256;
    };
  in
    map (n: {
      name = "${dir}/${n}";
      path = file;
    })
    names;
in
  assert lib.assertMsg (artifacts != []) "no artifacts found in ${toString verificationMetadata}";
    linkFarm "maven-repo" (lib.concatMap entries artifacts)
