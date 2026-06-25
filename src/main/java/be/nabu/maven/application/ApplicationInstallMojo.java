package be.nabu.maven.application;

import java.io.File;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

@Mojo(name = "install-application", defaultPhase = LifecyclePhase.PROCESS_RESOURCES, threadSafe = true)
public class ApplicationInstallMojo extends AbstractMojo {

	@Parameter(property = "application.targetDirectory", required = true)
	private File targetDirectory;

	@Parameter(property = "application.artifact", required = true)
	private String artifact;

	@Override
	public void execute() throws MojoExecutionException, MojoFailureException {
		if (artifact == null || artifact.trim().isEmpty()) {
			throw new MojoExecutionException("No application artifact configured");
		}
		if (targetDirectory == null) {
			throw new MojoExecutionException("No application target directory configured");
		}
		getLog().info("Application install plugin placeholder for artifact " + artifact + " to " + targetDirectory.getAbsolutePath());
	}
}
