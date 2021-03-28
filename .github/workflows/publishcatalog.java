///usr/bin/env jbang "$0" "$@" ; exit $?
//DEPS info.picocli:picocli:4.6.1
//DEPS io.quarkus:quarkus-devtools-registry-client:1.13.0.Final
//DEPS org.eclipse.jgit:org.eclipse.jgit:5.11.0.202103091610-r

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.MessageFormat;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import io.quarkus.registry.catalog.json.JsonCatalogMapperHelper;
import org.apache.maven.artifact.repository.metadata.Metadata;
import org.apache.maven.artifact.repository.metadata.io.xpp3.MetadataXpp3Reader;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.jboss.logging.Logger;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(name = "publishcatalog", mixinStandardHelpOptions = true, version = "publishcatalog 0.1",
        description = "publishcatalog made with jbang")
class publishcatalog implements Callable<Integer> {

    private static final String MAVEN_CENTRAL = "https://repo1.maven.org/maven2/";

    private static final Logger log = Logger.getLogger(publishcatalog.class);

    private static final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .build();

    @Option(names = {"-w", "--working-directory"}, description = "The working directory", required = true)
    private Path workingDirectory;

    @Option(names = {"-u", "--registry-url"}, description = "The Extension Registry URL", required = true, defaultValue = "${REGISTRY_URL}")
    private URI registryURL;

    @Option(names = {"-t", "--token"}, description = "The token to use when authenticating to the admin endpoint", required = true, defaultValue = "${REGISTRY_TOKEN}")
    private String token;

    @Option(names = {"-sv", "--skip-version-check"}, description = "Skip Version Check?", defaultValue = "${SKIP_VERSION_CHECK}")
    private boolean skipVersionCheck;

    @Option(names = {"-d", "--dry-run"}, description = "Dry Run? If true, does not change the YAML file and does not publish to the registry", defaultValue = "${DRY_RUN}")
    private boolean dryRun;

    private final ObjectMapper yamlMapper;

    private Git git;

    public static void main(String... args) {
        int exitCode = new CommandLine(new publishcatalog()).execute(args);
        System.exit(exitCode);
    }

    public publishcatalog() {
        this.yamlMapper = new YAMLMapper();
        JsonCatalogMapperHelper.initMapper(yamlMapper);
    }

    @Override
    public Integer call() throws Exception {
        if (dryRun) {
            log.warn("Running in dry-run mode. No files will be changed or posted to the registry");
        }
        try (Git gitHandle = Git.open(workingDirectory.toFile())) {
            this.git = gitHandle;
            list(workingDirectory.resolve("platforms"), this::processCatalog);
            list(workingDirectory.resolve("extensions"), this::processExtension);
        }
        return 0;
    }


    private void list(Path path, Consumer<Path> consumer) throws IOException {
        try (Stream<Path> files = Files.list(path)) {
            files.forEach(consumer);
        }
    }

    private void processCatalog(Path platformYaml) {
        try {
            log.info("---------------------------------------------------------------");
            ObjectNode tree = (ObjectNode) yamlMapper.readTree(platformYaml.toFile());
            String repository = tree.path("maven-repository").asText(MAVEN_CENTRAL);
            String groupId = tree.get("group-id").asText();
            String artifactId = tree.get("artifact-id").asText();
            log.infof("Fetching latest version for %s:%s", groupId, artifactId);
            ArrayNode versionsNode = tree.withArray("versions");
            // Get Latest Version
            String latestVersion = getLatestVersion(repository, groupId, artifactId);
            if (!skipVersionCheck) {
                if (containsValue(versionsNode, latestVersion)) {
                    log.warnf("%s:%s version %s was read previously. Skipping", groupId, artifactId, latestVersion);
                    return;
                } else {
                    versionsNode.insert(0, latestVersion);
                }
            }
            String classifier = tree.path("classifier").asText();
            if (tree.get("classifier-as-version").asBoolean()) {
                classifier = latestVersion;
            }
            // Get Extension YAML
            byte[] jsonPlatform = readCatalog(repository, groupId, artifactId, latestVersion, classifier);

            // Publish
            log.infof("Publishing %s:%s:%s", groupId, artifactId, latestVersion);
            if (!dryRun) {
                publishCatalog(jsonPlatform);
                if (!skipVersionCheck) {
                    // Write version
                    yamlMapper.writeValue(platformYaml.toFile(), tree);
                    // Git commit
                    gitCommit(platformYaml, "Add " + latestVersion + " to " + workingDirectory.resolve(platformYaml).normalize());
                }
            }
        } catch (IOException e) {
            log.info("---------------------------------------------------------------");
        }

    }

    private void processExtension(Path extensionYaml) {
        try {
            log.info("---------------------------------------------------------------");
            // Read
            ObjectNode tree = (ObjectNode) yamlMapper.readTree(extensionYaml.toFile());
            String repository = tree.path("maven-repository").asText(MAVEN_CENTRAL);
            String groupId = tree.get("group-id").asText();
            String artifactId = tree.get("artifact-id").asText();
            log.infof("Fetching latest version for %s:%s", groupId, artifactId);
            ArrayNode versionsNode = tree.withArray("versions");
            // Get Latest Version
            String latestVersion = getLatestVersion(repository, groupId, artifactId);
            // Compare if not already in descriptor
            if (!skipVersionCheck) {
                if (containsValue(versionsNode, latestVersion)) {
                    log.warnf("%s:%s version %s was read previously. Skipping", groupId, artifactId, latestVersion);
                    return;
                } else {
                    versionsNode.insert(0, latestVersion);
                }
            }
            // Get Extension YAML
            byte[] jsonExtension = readExtension(repository, groupId, artifactId, latestVersion);
            // Publish
            log.infof("Publishing %s:%s:%s", groupId, artifactId, latestVersion);
            if (!dryRun) {
                publishExtension(jsonExtension);
                if (!skipVersionCheck) {
                    // Write version
                    yamlMapper.writeValue(extensionYaml.toFile(), tree);
                    // Git commit
                    gitCommit(extensionYaml, "Add " + latestVersion + " to " + workingDirectory.resolve(extensionYaml).normalize());
                }
            }
        } catch (IOException e) {
            log.error("Error while processing extension", e);
        }
        log.info("---------------------------------------------------------------");
    }

    private boolean containsValue(ArrayNode versionsNode, String latestVersion) {
        for (JsonNode node : versionsNode) {
            if (latestVersion.equals(node.asText())) {
                return true;
            }
        }
        return false;
    }

    private String getLatestVersion(String repository, String groupId, String artifactId) throws IOException {
        URL metadataURL = new URL(MessageFormat.format("{0}{1}/{2}/maven-metadata.xml",
                                                       Objects.toString(repository, MAVEN_CENTRAL),
                                                       groupId.replace('.', '/'),
                                                       artifactId));
        try (InputStream is = metadataURL.openStream()) {
            MetadataXpp3Reader metadataReader = new MetadataXpp3Reader();
            Metadata metadata = metadataReader.read(is);
            return metadata.getVersioning().getLatest();
        } catch (XmlPullParserException e) {
            throw new IOException("Invalid Metadata", e);
        }
    }

    private byte[] readCatalog(String repository, String groupId, String artifactId, String version, String classifier) throws IOException {
        URL platformJson;
        if (classifier == null) {
            platformJson = new URL(MessageFormat.format("{0}{1}/{2}/{3}/{2}-{3}.json",
                                                        Objects.toString(repository, MAVEN_CENTRAL),
                                                        groupId.replace('.', '/'),
                                                        artifactId,
                                                        version));
        } else {
//            https://repo1.maven.org/maven2/io/quarkus/quarkus-bom-quarkus-platform-descriptor/1.13.0.Final/quarkus-bom-quarkus-platform-descriptor-1.13.0.Final-1.13.0.Final.json
            platformJson = new URL(MessageFormat.format("{0}{1}/{2}/{3}/{2}-{4}-{3}.json",
                                                        Objects.toString(repository, MAVEN_CENTRAL),
                                                        groupId.replace('.', '/'),
                                                        artifactId,
                                                        version,
                                                        classifier));
        }
        try (InputStream is = platformJson.openStream()) {
            return is.readAllBytes();
        }
    }

    private byte[] readExtension(String repository, String groupId, String artifactId, String version) throws IOException {
        URL extensionJarURL = new URL(MessageFormat.format("jar:{0}{1}/{2}/{3}/{2}-{3}.jar!/META-INF/quarkus-extension.yaml",
                                                           Objects.toString(repository, MAVEN_CENTRAL),
                                                           groupId.replace('.', '/'),
                                                           artifactId,
                                                           version));
        try (InputStream is = extensionJarURL.openStream()) {
            return is.readAllBytes();
        }
    }

    private void publishExtension(byte[] extension) throws IOException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(registryURL.resolve("/admin/v1/extension"))
                .timeout(Duration.ofMinutes(2))
                .header("Content-Type", "application/yaml")
                .header("Token", token)
                .POST(HttpRequest.BodyPublishers.ofByteArray(extension))
                .build();

        HttpResponse<String> response = null;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            throw new IOException("Interrupted", e);
        }
        if (response.statusCode() == HttpURLConnection.HTTP_CONFLICT) {
            log.info("Conflict, version already exists. Ignoring");
            return;
        }
        if (response.statusCode() != HttpURLConnection.HTTP_ACCEPTED) {
            throw new IOException(response.statusCode() + " -> " + response.body());
        } else {
            log.info("Extension published");
        }
    }


    private void publishCatalog(byte[] jsonPlatform) throws IOException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(registryURL.resolve("/admin/v1/extension/catalog"))
                .timeout(Duration.ofMinutes(2))
                .header("Content-Type", "application/json")
                .header("Token", token)
                .POST(HttpRequest.BodyPublishers.ofByteArray(jsonPlatform))
                .build();

        HttpResponse<String> response = null;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            throw new IOException("Interrupted", e);
        }
        if (response.statusCode() == HttpURLConnection.HTTP_CONFLICT) {
            log.info("Conflict, version already exists. Ignoring");
            return;
        }
        if (response.statusCode() != HttpURLConnection.HTTP_ACCEPTED) {
            throw new IOException(response.statusCode() + " -> " + response.body());
        } else {
            log.info("Platform published");
        }
    }

    public void gitCommit(Path file, String message) throws IOException {
        try {
            git.add().addFilepattern(workingDirectory.resolve(file).normalize().toString()).call();
            git.commit().setSign(false).setMessage(message).call();
        } catch (GitAPIException e) {
            throw new IOException(e);
        }
    }
}
