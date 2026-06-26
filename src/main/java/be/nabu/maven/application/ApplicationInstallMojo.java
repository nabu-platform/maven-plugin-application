package be.nabu.maven.application;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.DefaultArtifact;
import org.apache.maven.artifact.handler.ArtifactHandler;
import org.apache.maven.artifact.handler.manager.ArtifactHandlerManager;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.model.Dependency;
import org.apache.maven.project.MavenProject;
import org.apache.maven.shared.transfer.artifact.resolve.ArtifactResolver;
import org.apache.maven.shared.transfer.artifact.resolve.ArtifactResolverException;
import org.apache.maven.shared.transfer.artifact.resolve.ArtifactResult;

@Mojo(name = "install-application", defaultPhase = LifecyclePhase.PROCESS_RESOURCES, threadSafe = true)
public class ApplicationInstallMojo extends AbstractMojo {

	private static final Map<String, String> APPLICATION_COORDINATES = new LinkedHashMap<String, String>();

	static {
		APPLICATION_COORDINATES.put("integrator", "be.nabu.packaging:packaging-integrator:zip");
		APPLICATION_COORDINATES.put("developer", "be.nabu.packaging:packaging-developer:zip");
		APPLICATION_COORDINATES.put("cli", "be.nabu.packaging:packaging-cli:zip");
	}

	@Component
	private ArtifactResolver artifactResolver;

	@Component
	private ArtifactHandlerManager artifactHandlerManager;

	@Parameter(defaultValue = "${session}", readonly = true, required = true)
	private MavenSession session;

	@Parameter(defaultValue = "${project}", readonly = true, required = true)
	private MavenProject project;

	@Parameter(property = "application.targetDirectory")
	private File targetDirectory;

	@Parameter(property = "application.artifact")
	private String artifact;

	@Parameter(property = "application.name", defaultValue = "integrator")
	private String applicationName;

	@Parameter
	private Map<String, String> systemProperties;

	@Override
	public void execute() throws MojoExecutionException, MojoFailureException {
		String coordinates = resolveCoordinates();
		File resolvedTargetDirectory = resolveTargetDirectory();
		ArtifactResult artifactResult = resolveArtifact(coordinates);
		File artifactFile = artifactResult.getArtifact().getFile();
		if (artifactFile == null || !artifactFile.isFile()) {
			throw new MojoExecutionException("Resolved application artifact has no file: " + coordinates);
		}
		getLog().info("Installing application artifact " + coordinates + " to " + resolvedTargetDirectory.getAbsolutePath());
		unzip(artifactFile.toPath(), resolvedTargetDirectory.toPath());
		mergeServerProperties(resolvedTargetDirectory.toPath());
	}

	private String resolveCoordinates() throws MojoExecutionException {
		if (artifact != null && !artifact.trim().isEmpty()) {
			return artifact.trim();
		}
		String key = applicationName == null ? "integrator" : applicationName.trim().toLowerCase();
		String coordinates = APPLICATION_COORDINATES.get(key);
		if (coordinates == null) {
			throw new MojoExecutionException(
				"Unknown application '" + applicationName + "'. Supported values are: " + APPLICATION_COORDINATES.keySet()
			);
		}
		return coordinates;
	}

	private File resolveTargetDirectory() throws MojoExecutionException {
		if (targetDirectory != null) {
			return targetDirectory;
		}
		File basedir = project != null ? project.getBasedir() : null;
		if (basedir == null) {
			throw new MojoExecutionException("Could not determine project base directory for default install target");
		}
		return basedir;
	}

	private ArtifactResult resolveArtifact(String coordinates) throws MojoExecutionException {
		String[] parts = coordinates.split(":");
		if (parts.length != 3 && parts.length != 4) {
			throw new MojoExecutionException(
				"Application artifact must be '<groupId>:<artifactId>:<type>' or '<groupId>:<artifactId>:<type>:<version>'"
			);
		}
		String groupId = parts[0];
		String artifactId = parts[1];
		String type = parts[2];
		String version = parts.length == 4 ? parts[3] : findManagedVersion(groupId, artifactId, type);
		ArtifactHandler artifactHandler = artifactHandlerManager.getArtifactHandler(type);
		if (artifactHandler == null) {
			throw new MojoExecutionException("No artifact handler registered for type '" + type + "'");
		}
		Artifact toResolve = new DefaultArtifact(groupId, artifactId, version, null, type, null, artifactHandler);
		try {
			return artifactResolver.resolveArtifact(session.getProjectBuildingRequest(), toResolve);
		}
		catch (ArtifactResolverException exc) {
			throw new MojoExecutionException("Could not resolve application artifact " + coordinates, exc);
		}
	}

	private String findManagedVersion(String groupId, String artifactId, String type) throws MojoExecutionException {
		if (project == null || project.getDependencyManagement() == null || project.getDependencyManagement().getDependencies() == null) {
			throw new MojoExecutionException("No dependencyManagement available to resolve application artifact version for " + groupId + ":" + artifactId);
		}
		for (Dependency dependency : project.getDependencyManagement().getDependencies()) {
			if (groupId.equals(dependency.getGroupId())
				&& artifactId.equals(dependency.getArtifactId())
				&& type.equals(Objects.toString(dependency.getType(), "jar"))) {
				String version = dependency.getVersion();
				if (version != null && !version.trim().isEmpty()) {
					return version.trim();
				}
			}
		}
		throw new MojoExecutionException("No managed version found for application artifact " + groupId + ":" + artifactId + ":" + type);
	}

	private void unzip(Path zipFile, Path target) throws MojoExecutionException {
		try {
			Files.createDirectories(target);
			byte[] buffer = new byte[8192];
			try (InputStream input = new BufferedInputStream(new FileInputStream(zipFile.toFile()));
				 ZipInputStream archive = new ZipInputStream(input)) {
				ZipEntry entry;
				while ((entry = archive.getNextEntry()) != null) {
					Path output = target.resolve(entry.getName()).normalize();
					if (!output.startsWith(target)) {
						throw new MojoExecutionException("Refusing to extract entry outside target directory: " + entry.getName());
					}
					if (entry.isDirectory()) {
						Files.createDirectories(output);
					}
					else {
						Path parent = output.getParent();
						if (parent != null) {
							Files.createDirectories(parent);
						}
						try (OutputStream result = new BufferedOutputStream(new FileOutputStream(output.toFile()))) {
							int read;
							while ((read = archive.read(buffer)) >= 0) {
								result.write(buffer, 0, read);
							}
						}
					}
					archive.closeEntry();
				}
			}
		}
		catch (IOException exc) {
			throw new MojoExecutionException("Could not unzip application artifact " + zipFile, exc);
		}
	}

	private void mergeServerProperties(Path targetDirectoryPath) throws MojoExecutionException {
		mergePropertiesFile(targetDirectoryPath.resolve("server.properties"), systemProperties, "server.properties");
	}

	private void mergePropertiesFile(Path propertiesPath, Map<String, String> values, String description) throws MojoExecutionException {
		if (values == null || values.isEmpty()) {
			return;
		}
		Properties properties = new Properties();
		if (Files.exists(propertiesPath)) {
			try (InputStream input = new BufferedInputStream(new FileInputStream(propertiesPath.toFile()))) {
				properties.load(input);
			}
			catch (IOException exc) {
				throw new MojoExecutionException("Could not read existing " + description + " at " + propertiesPath, exc);
			}
		}
		for (Map.Entry<String, String> entry : values.entrySet()) {
			String key = entry.getKey();
			String value = entry.getValue();
			if (key == null || key.trim().isEmpty()) {
				continue;
			}
			properties.setProperty(key, Objects.toString(value, ""));
		}
		try {
			Path parent = propertiesPath.getParent();
			if (parent != null) {
				Files.createDirectories(parent);
			}
			try (OutputStream output = new BufferedOutputStream(new FileOutputStream(propertiesPath.toFile()))) {
				properties.store(output, "Updated by maven-plugin-application");
			}
		}
		catch (IOException exc) {
			throw new MojoExecutionException("Could not write " + description + " at " + propertiesPath, exc);
		}
	}
}
