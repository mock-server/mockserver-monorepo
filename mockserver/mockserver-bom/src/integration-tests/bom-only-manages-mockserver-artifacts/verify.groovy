import groovy.xml.XmlSlurper // Groovy 4 moved XmlSlurper out of groovy.util and stopped auto-importing it

// Guard for GitHub issue #2684 — see this project's pom.xml for the scenario.
//
// Bindings provided by maven-invoker-plugin: basedir (File, the cloned+filtered project),
// localRepositoryPath (File, the repo the BOM was installed into), context, mavenVersion.

// --- Assertion 1: the consumer keeps its OWN oauth2-oidc-sdk version AND compile scope ---
File depList = new File(basedir, 'dependency-list.txt')
assert depList.exists() : "dependency-list.txt was not produced by the consumer build"
String deps = depList.text

assert deps =~ /com\.nimbusds:oauth2-oidc-sdk:jar:11\.37\.2:compile/ :
        "Expected the consumer's own oauth2-oidc-sdk 11.37.2 at compile scope, but importing " +
        "mockserver-bom changed it. Resolved dependencies:\n${deps}"

assert !(deps =~ /oauth2-oidc-sdk:jar:11\.38\.2/) :
        "mockserver-bom hijacked the oauth2-oidc-sdk VERSION to MockServer's internal pin (#2684). " +
        "Resolved dependencies:\n${deps}"

assert !(deps =~ /oauth2-oidc-sdk:[^\r\n]*:test/) :
        "mockserver-bom hijacked the oauth2-oidc-sdk SCOPE to test (#2684). " +
        "Resolved dependencies:\n${deps}"

// --- Assertion 2: the PUBLISHED BOM manages ONLY org.mock-server artifacts ---
// This is the real invariant — it turns "the BOM publishes only our own artifacts" into a build failure.
def consumer = new XmlSlurper().parse(new File(basedir, 'pom.xml'))
String version = consumer.properties.'mockserver.version'.text()
assert version : "could not read the filtered mockserver.version from the consumer pom"

File bomPom = new File(localRepositoryPath, "org/mock-server/mockserver-bom/${version}/mockserver-bom-${version}.pom")
assert bomPom.exists() : "published mockserver-bom pom not found at ${bomPom}"

def bom = new XmlSlurper().parse(bomPom)
def managed = bom.dependencyManagement.dependencies.dependency
assert managed.size() > 0 : "the published mockserver-bom manages no dependencies at all"

def foreign = managed.findAll { it.groupId.text() != 'org.mock-server' }
        .collect { "${it.groupId.text()}:${it.artifactId.text()}" }
assert foreign.isEmpty() :
        "the published mockserver-bom must manage ONLY org.mock-server artifacts (#2684), " +
        "but it also manages: ${foreign}"

// --- Proof of execution: fail-closed marker (see this project's pom.xml) ---
// Written as the LAST act, so its presence can only be explained by this hook having run to
// completion (every assertion above passed). The maven-enforcer requireFilesExist execution in
// pom.xml asserts this marker exists after the invoker run; if the invoker plugin ever SILENTLY
// SKIPS this hook (no Groovy interpreter on its classpath, or a hook-filename mismatch), the marker
// is absent and the build fails LOUDLY instead of passing vacuously. The marker lives under the
// invoker project's target/, which the invoker goal's own `clean` wipes at the start of every run,
// so a stale marker from an earlier run cannot satisfy the check.
File markerDir = new File(basedir, 'target')
markerDir.mkdirs()
File marker = new File(markerDir, 'bom-guard-executed.marker')
marker.text = "verify.groovy (#2684 BOM guard) completed at ${new Date()} for project ${basedir.name}\n"

return true
