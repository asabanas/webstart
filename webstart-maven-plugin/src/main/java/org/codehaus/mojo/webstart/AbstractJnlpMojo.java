package org.codehaus.mojo.webstart;

/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import org.apache.commons.lang.StringUtils;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.resolver.filter.AndArtifactFilter;
import org.apache.maven.artifact.resolver.filter.ArtifactFilter;
import org.apache.maven.artifact.resolver.filter.ExcludesArtifactFilter;
import org.apache.maven.artifact.resolver.filter.IncludesArtifactFilter;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.PluginManager;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProjectHelper;
import org.apache.maven.settings.Settings;
import org.codehaus.mojo.webstart.generator.ExtensionGenerator;
import org.codehaus.mojo.webstart.generator.ExtensionGeneratorExtraConfig;
import org.codehaus.mojo.webstart.generator.Generator;
import org.codehaus.mojo.webstart.generator.JnplGeneratorExtraConfig;
import org.codehaus.mojo.webstart.util.IOUtil;
import org.codehaus.plexus.archiver.Archiver;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * @author <a href="jerome@coffeebreaks.org">Jerome Lacoste</a>
 * @version $Id$
 *          TODO how to propagate the -X argument to enable verbose?
 *          TODO initialize the jnlp alias and dname.o from pom.artifactId and pom.organization.name
 */
public abstract class AbstractJnlpMojo
    extends AbstractBaseJnlpMojo
{
    // ----------------------------------------------------------------------
    // Constants
    // ----------------------------------------------------------------------

    private static final String DEFAULT_TEMPLATE_LOCATION = "src/main/jnlp/template.vm";

    // ----------------------------------------------------------------------
    // Mojo Parameters
    // ----------------------------------------------------------------------

    /**
     * Represents the configuration element that specifies which of the current
     * project's dependencies will be included or excluded from the resources element
     * in the generated JNLP file.
     */
    public static class Dependencies
    {

        private boolean outputJarVersions;

        private List<String> includes;

        private List<String> excludes;

        /**
         * Returns the value of the flag that determines whether or not
         * the version attribute will be output in each jar resource element
         * in the generated JNLP file.
         *
         * @return The default output version flag.
         */
        public boolean getOutputJarVersions()
        {
            return outputJarVersions;
        }

        public List<String> getIncludes()
        {
            return includes;
        }

        public void setIncludes( List<String> includes )
        {
            this.includes = includes;
        }

        public List<String> getExcludes()
        {
            return excludes;
        }

        public void setExcludes( List<String> excludes )
        {
            this.excludes = excludes;
        }
    }

    /**
     * Flag to create the archive or not.
     *
     * @since 1.0-beta-2
     */
    @Parameter( defaultValue = "true" )
    private boolean makeArchive;

    /**
     * Flag to attach the archive or not to the project's build.
     *
     * @since 1.0-beta-2
     */
    @Parameter( defaultValue = "true" )
    private boolean attachArchive;

    /**
     * The path of the archive to generate if {@link #makeArchive} flag is on.
     *
     * @since 1.0-beta-4
     */
    @Parameter( defaultValue = "${project.build.directory}/${project.build.finalName}.zip" )
    private File archive;

    /**
     * The jnlp configuration element.
     */
    @Parameter
    private JnlpConfig jnlp;

    /**
     * [optional] extensions configuration.
     *
     * @since 1.0-beta-2
     */
    @Parameter
    private List<JnlpExtension> jnlpExtensions;

    /**
     * [optional] transitive dependencies filter - if omitted, the plugin will include all transitive dependencies.
     * Provided and test scope dependencies are always excluded.
     */
    @Parameter
    private Dependencies dependencies;

    /**
     * A placeholder for an obsoleted configuration element.
     * <p/>
     * This dummy parameter is here to force the plugin configuration to fail in case one
     * didn't properly migrate from 1.0-alpha-1 to 1.0-alpha-2 configuration.
     * <p/>
     * It will be removed before 1.0.
     */
    @Parameter
    private String keystore;

    /**
     */
    @Parameter( defaultValue = "${basedir}", readonly = true, required = true )
    private File basedir;

    /**
     * When set to true, this flag indicates that a version attribute should
     * be output in each of the jar resource elements in the generated
     * JNLP file.
     */
    @Parameter( defaultValue = "false" )
    private boolean outputJarVersions;

    // ----------------------------------------------------------------------
    // Components
    // ----------------------------------------------------------------------

    /**
     * The current user system settings for use in Maven. This is used for
     * <br/>
     * plugin manager API calls.
     */
    @Component
    private Settings settings;

    /**
     * The Zip archiver.
     */
    @Component( hint = "zip" )
    private Archiver zipArchiver;

    /**
     * The project helper used to attach the artifact produced by this plugin to the project.
     */
    @Component
    private MavenProjectHelper projectHelper;

    /**
     * The plugin manager instance used to resolve plugin descriptors.
     */
    @Component
    private PluginManager pluginManager;

    // ----------------------------------------------------------------------
    // Fields
    // ----------------------------------------------------------------------

    /**
     * the artifacts packaged in the webstart app
     */
    private List<Artifact> packagedJnlpArtifacts = new ArrayList<Artifact>();

    /**
     * the artifacts associated to each jnlp extension
     */
    private Map<JnlpExtension, List<Artifact>> extensionsJnlpArtifacts = new HashMap<JnlpExtension, List<Artifact>>();

    private Artifact artifactWithMainClass;

    // ----------------------------------------------------------------------
    // Mojo Implementation
    // ----------------------------------------------------------------------

    /**
     * {@inheritDoc}
     */
    public void execute()
        throws MojoExecutionException
    {

        boolean withExtensions = hasJnlpExtensions();

        if ( withExtensions )
        {
            prepareExtensions();
            findDefaultJnlpExtensionTemplateURL();
        }

        checkInput();

        findDefaultJnlpTemplateURL();

        getLog().debug( "using work directory " + getWorkDirectory() );
        getLog().debug( "using library directory " + getLibDirectory() );

        IOUtil ioUtil = getIoUtil();

        //
        // prepare layout
        //

        ioUtil.makeDirectoryIfNecessary( getWorkDirectory() );
        ioUtil.makeDirectoryIfNecessary( getLibDirectory() );

        try
        {
            ioUtil.copyResources( getResourcesDirectory(), getWorkDirectory() );

            artifactWithMainClass = null;

            processDependencies();

            if ( withExtensions )
            {
                processExtensionsDependencies();
            }

            if ( artifactWithMainClass == null )
            {
                throw new MojoExecutionException(
                    "didn't find artifact with main class: " + jnlp.getMainClass() + ". Did you specify it? " );
            }

            // native libs
            // FIXME

            /*
            for( Iterator it = getNativeLibs().iterator(); it.hasNext(); ) {
                Artifact artifact = ;
                Artifact copiedArtifact =

                // similar to what we do for jars, except that we must pack them into jar instead of copying.
                // them
                    File nativeLib = artifact.getFile()
                    if(! nativeLib.endsWith( ".jar" ) ){
                        getLog().debug("Wrapping native library " + artifact + " into jar." );
                        File nativeLibJar = new File( applicationFolder, xxx + ".jar");
                        Jar jarTask = new Jar();
                        jarTask.setDestFile( nativeLib );
                        jarTask.setBasedir( basedir );
                        jarTask.setIncludes( nativeLib );
                        jarTask.execute();

                        nativeLibJar.setLastModified( nativeLib.lastModified() );

                        copiedArtifact = new ....
                    } else {
                        getLog().debug( "Copying native lib " + artifact );
                        copyFileToDirectory( artifact.getFile(), applicationFolder );

                        copiedArtifact = artifact;
                    }
                    copiedNativeArtifacts.add( copiedArtifact );
                }
            }
            */

            //
            // pack200 and jar signing
            //
            if ( ( isPack200() || getSign() != null ) && getLog().isDebugEnabled() )
            {
                logCollection(
                    "Some dependencies may be skipped. Here's the list of the artifacts that should be signed/packed: ",
                    getModifiedJnlpArtifacts() );
            }

            signOrRenameJars();
            packJars();
            generateJnlpFile( getWorkDirectory() );
            if ( withExtensions )
            {
                generateJnlpExtensionsFile( getWorkDirectory() );
            }

            if ( makeArchive )
            {
                // package the zip. Note this is very simple. Look at the JarMojo which does more things.
                // we should perhaps package as a war when inside a project with war packaging ?

                ioUtil.makeDirectoryIfNecessary( archive.getParentFile() );

                ioUtil.deleteFile( archive );

                verboseLog( "Will create archive at location: " + archive );

                zipArchiver.addDirectory( getWorkDirectory() );
                zipArchiver.setDestFile( archive );
                getLog().debug( "about to call createArchive" );
                zipArchiver.createArchive();

                if ( attachArchive )
                {
                    // maven 2 version 2.0.1 method
                    projectHelper.attachArtifact( getProject(), "zip", archive );
                }
            }
        }
        catch ( MojoExecutionException e )
        {
            throw e;
        }
        catch ( Exception e )
        {
            throw new MojoExecutionException( "Failure to run the plugin: ", e );
        }
    }

    // ----------------------------------------------------------------------
    // Public methods
    // ----------------------------------------------------------------------

    public List<JnlpExtension> getJnlpExtensions()
    {
        return jnlpExtensions;
    }

    public boolean hasJnlpExtensions()
    {
        return jnlpExtensions != null && !jnlpExtensions.isEmpty();
    }

    public List<Artifact> getPackagedJnlpArtifacts()
    {
        return packagedJnlpArtifacts;
    }

    public Map<JnlpExtension, List<Artifact>> getExtensionsJnlpArtifacts()
    {
        return extensionsJnlpArtifacts;
    }

    public boolean isArtifactWithMainClass( Artifact artifact )
    {
        final boolean b = artifactWithMainClass.equals( artifact );
        getLog().debug( "compare " + artifactWithMainClass + " with " + artifact + ": " + b );
        return b;
    }

    /**
     * Returns the flag that indicates whether or not a version attribute
     * should be output in each jar resource element in the generated
     * JNLP file. The default is false.
     *
     * @return Returns the value of the {@code outputJarVersions} property.
     */
    public boolean isOutputJarVersions()
    {
        return this.outputJarVersions;
    }

    /**
     * Sets the flag that indicates whether or not a version attribute
     * should be output in each jar resource element in the generated
     * JNLP file. The default is false.
     *
     * @param outputJarVersions new value of the {@link #outputJarVersions} field
     */
    public void setOutputJarVersions( boolean outputJarVersions )
    {
        this.outputJarVersions = outputJarVersions;
    }

    // ----------------------------------------------------------------------
    // Protected Methods
    // ----------------------------------------------------------------------

    protected JnlpConfig getJnlp()
    {
        return jnlp;
    }

    protected Dependencies getDependencies()
    {
        return this.dependencies;
    }

    // ----------------------------------------------------------------------
    // Private Methods
    // ----------------------------------------------------------------------

    /**
     * Detects improper includes/excludes configuration.
     *
     * @throws MojoExecutionException if at least one of the specified includes or excludes matches no artifact,
     *                                false otherwise
     */
    void checkDependencies()
        throws MojoExecutionException
    {
        if ( dependencies == null )
        {
            return;
        }

        boolean failed = false;

        Collection<Artifact> artifacts = getProject().getArtifacts();

        getLog().debug( "artifacts: " + artifacts.size() );

        if ( dependencies.getIncludes() != null && !dependencies.getIncludes().isEmpty() )
        {
            failed = checkDependencies( dependencies.getIncludes(), artifacts );
        }
        if ( dependencies.getExcludes() != null && !dependencies.getExcludes().isEmpty() )
        {
            failed = checkDependencies( dependencies.getExcludes(), artifacts ) || failed;
        }

        if ( failed )
        {
            throw new MojoExecutionException(
                "At least one specified dependency is incorrect. Review your project configuration." );
        }
    }

    /**
     * @param patterns  list of patterns to test over artifacts
     * @param artifacts collection of artifacts to check
     * @return true if at least one of the pattern in the list matches no artifact, false otherwise
     */
    private boolean checkDependencies( List<String> patterns, Collection<Artifact> artifacts )
    {
        if ( dependencies == null )
        {
            return false;
        }

        boolean failed = false;
        for ( String pattern : patterns )
        {
            failed = ensurePatternMatchesAtLeastOneArtifact( pattern, artifacts ) || failed;
        }
        return failed;
    }

    /**
     * @param pattern   pattern to test over artifacts
     * @param artifacts collection of artifacts to check
     * @return true if filter matches no artifact, false otherwise *
     */
    private boolean ensurePatternMatchesAtLeastOneArtifact( String pattern, Collection<Artifact> artifacts )
    {
        List<String> onePatternList = new ArrayList<String>();
        onePatternList.add( pattern );
        ArtifactFilter filter = new IncludesArtifactFilter( onePatternList );

        boolean noMatch = true;
        for ( Artifact artifact : artifacts )
        {
            getLog().debug( "checking pattern: " + pattern + " against " + artifact );

            if ( filter.include( artifact ) )
            {
                noMatch = false;
                break;
            }
        }
        if ( noMatch )
        {
            getLog().error( "pattern: " + pattern + " doesn't match any artifact." );
        }
        return noMatch;
    }

    /**
     * Iterate through all the top level and transitive dependencies declared in the project and
     * collect all the runtime scope dependencies for inclusion in the .zip and signing.
     *
     * @throws IOException if could not process dependencies
     */
    private void processDependencies()
        throws IOException
    {

        processDependency( getProject().getArtifact() );

        AndArtifactFilter filter = new AndArtifactFilter();
        // filter.add( new ScopeArtifactFilter( dependencySet.getScope() ) );

        if ( dependencies != null && dependencies.getIncludes() != null && !dependencies.getIncludes().isEmpty() )
        {
            filter.add( new IncludesArtifactFilter( dependencies.getIncludes() ) );
        }
        if ( dependencies != null && dependencies.getExcludes() != null && !dependencies.getExcludes().isEmpty() )
        {
            filter.add( new ExcludesArtifactFilter( dependencies.getExcludes() ) );
        }

        Collection<Artifact> artifacts =
            isExcludeTransitive() ? getProject().getDependencyArtifacts() : getProject().getArtifacts();

        for ( Artifact artifact : artifacts )
        {
            if ( filter.include( artifact ) )
            {
                processDependency( artifact );
            }
        }
    }

    private void processDependency( Artifact artifact )
        throws IOException
    {
        // TODO: scope handler
        // Include runtime and compile time libraries
        if ( !Artifact.SCOPE_SYSTEM.equals( artifact.getScope() ) &&
            !Artifact.SCOPE_PROVIDED.equals( artifact.getScope() ) &&
            !Artifact.SCOPE_TEST.equals( artifact.getScope() ) )
        {
            String type = artifact.getType();
            if ( "jar".equals( type ) || "ejb-client".equals( type ) )
            {

                // FIXME when signed, we should update the manifest.
                // see http://www.mail-archive.com/turbine-maven-dev@jakarta.apache.org/msg08081.html
                // and maven1: maven-plugins/jnlp/src/main/org/apache/maven/jnlp/UpdateManifest.java
                // or shouldn't we?  See MOJO-7 comment end of October.
                final File toCopy = artifact.getFile();

                if ( toCopy == null )
                {
                    getLog().error( "artifact with no file: " + artifact );
                    getLog().error( "artifact download url: " + artifact.getDownloadUrl() );
                    getLog().error( "artifact repository: " + artifact.getRepository() );
                    getLog().error( "artifact repository: " + artifact.getVersion() );
                    throw new IllegalStateException(
                        "artifact " + artifact + " has no matching file, why? Check the logs..." );
                }

                boolean copied = copyJarAsUnprocessedToDirectoryIfNecessary( toCopy, getLibDirectory() );

                if ( copied )
                {

                    String name = toCopy.getName();
                    getModifiedJnlpArtifacts().add( name.substring( 0, name.lastIndexOf( '.' ) ) );

                }

                packagedJnlpArtifacts.add( artifact );

                boolean containsMainClass = getArtifactUtil().artifactContainsMainClass( artifact, jnlp );

                if ( containsMainClass )
                {
                    if ( artifactWithMainClass == null )
                    {
                        artifactWithMainClass = artifact;
                        getLog().debug(
                            "Found main jar. Artifact " + artifactWithMainClass + " contains the main class: " +
                                jnlp.getMainClass() );
                    }
                    else
                    {
                        getLog().warn(
                            "artifact " + artifact + " also contains the main class: " + jnlp.getMainClass() +
                                ". IGNORED." );
                    }
                }
            }
            else
            // FIXME how do we deal with native libs?
            // we should probably identify them and package inside jars that we timestamp like the native lib
            // to avoid repackaging every time. What are the types of the native libs?
            {
                verboseLog( "Skipping artifact of type " + type + " for " + getLibDirectory().getName() );
            }
            // END COPY
        }
        else
        {
            verboseLog( "Skipping artifact of scope " + artifact.getScope() + " for " + getLibDirectory().getName() );
        }
    }

    private void generateJnlpFile( File outputDirectory )
        throws MojoExecutionException
    {
        if ( StringUtils.isBlank( jnlp.getOutputFile() ) )
        {
            getLog().debug( "Jnlp output file name not specified. Using default output file name: launch.jnlp." );
            jnlp.setOutputFile( "launch.jnlp" );
        }
        File jnlpOutputFile = new File( outputDirectory, jnlp.getOutputFile() );

        File templateDirectory = getTemplateDirectory();

        if ( StringUtils.isNotBlank( jnlp.getInputTemplateResourcePath() ) )
        {
            templateDirectory = new File( jnlp.getInputTemplateResourcePath() );
        }

        if ( StringUtils.isBlank( jnlp.getInputTemplate() ) )
        {
            getLog().debug( "Jnlp template file name not specified. Checking if default output file name exists: " +
                                DEFAULT_TEMPLATE_LOCATION );

            File templateFile = new File( templateDirectory, DEFAULT_TEMPLATE_LOCATION );

            if ( templateFile.isFile() )
            {
                jnlp.setInputTemplate( DEFAULT_TEMPLATE_LOCATION );
            }
            else
            {
                getLog().debug( "Jnlp template file not found in default location. Using inbuilt one." );
            }
        }
        else
        {
            File templateFile = new File( templateDirectory, jnlp.getInputTemplate() );

            if ( !templateFile.isFile() )
            {
                throw new MojoExecutionException(
                    "The specified JNLP template does not exist: [" + templateFile + "]" );
            }
        }
        String templateFileName = jnlp.getInputTemplate();

        Generator jnlpGenerator =
            new Generator( getLog(), getProject(), this, "default-jnlp-template.vm", templateDirectory, jnlpOutputFile,
                           templateFileName, getJnlp().getMainClass(), getWebstartJarURLForVelocity(), getEncoding() );

        jnlpGenerator.setExtraConfig( new JnplGeneratorExtraConfig( jnlp, getCodebase() ) );

        try
        {
            jnlpGenerator.generate();
        }
        catch ( Exception e )
        {
            getLog().debug( e.toString() );
            throw new MojoExecutionException( "Could not generate the JNLP deployment descriptor", e );
        }
    }

    private void logCollection( final String prefix, final Collection collection )
    {
        getLog().debug( prefix + " " + collection );
        if ( collection == null )
        {
            return;
        }
        for ( Object aCollection : collection )
        {
            getLog().debug( prefix + aCollection );
        }
    }

    private void checkInput()
        throws MojoExecutionException
    {

        getLog().debug( "a fact " + getArtifactFactory() );
        getLog().debug( "a resol " + getArtifactResolver() );
        getLog().debug( "basedir " + this.basedir );
        getLog().debug( "gzip " + isGzip() );
        getLog().debug( "pack200 " + isPack200() );
        getLog().debug( "project " + this.getProject() );
        getLog().debug( "zipArchiver " + this.zipArchiver );
        getLog().debug( "verifyjar " + isVerifyjar() );
        getLog().debug( "verbose " + isVerbose() );

        checkPack200();
        checkDependencies();

        if ( jnlp != null && jnlp.getResources() != null )
        {
            throw new MojoExecutionException(
                "The <jnlp><resources> configuration element is obsolete. Use <resourcesDirectory> instead." );
        }

        // FIXME
        /*
        if ( !"pom".equals( getProject().getPackaging() ) ) {
           throw new MojoExecutionException( "'" + getProject().getPackaging() + "' packaging unsupported. Use 'pom'" );
        }
        */
    }

    private void checkExtension( JnlpExtension extension )
        throws MojoExecutionException
    {
        if ( StringUtils.isEmpty( extension.getName() ) )
        {
            throw new MojoExecutionException( "JnlpExtension name is mandatory. Review your project configuration." );
        }
        if ( StringUtils.isEmpty( extension.getVendor() ) )
        {
            throw new MojoExecutionException( "JnlpExtension vendor is mandatory. Review your project configuration." );
        }
        if ( StringUtils.isEmpty( extension.getTitle() ) )
        {
            throw new MojoExecutionException( "JnlpExtension name is title. Review your project configuration." );
        }
        if ( extension.getIncludes() == null || extension.getIncludes().isEmpty() )
        {
            throw new MojoExecutionException(
                "JnlpExtension need at least one include artifact. Review your project configuration." );
        }

    }

    /**
     * Prepare extensions.
     * <p/>
     * Copy all includes of all extensions as to be excluded.
     *
     * @throws MojoExecutionException if could not prepare extensions
     */
    private void prepareExtensions()
        throws MojoExecutionException
    {
        List<String> includes = new ArrayList<String>();
        for ( JnlpExtension extension : jnlpExtensions )
        {
            // Check extensions (mandatory name, title and vendor and at least one include)

            checkExtension( extension );

            for ( String o : extension.getIncludes() )
            {
                includes.add( o.trim() );
            }

            if ( extension.getOutputFile() == null || extension.getOutputFile().length() == 0 )
            {
                String name = extension.getName() + ".jnlp";
                verboseLog(
                    "Jnlp extension output file name not specified. Using default output file name: " + name + "." );
                extension.setOutputFile( name );
            }
        }
        // copy all includes libs fro extensions to be exclude from the mojo
        // treatments (extensions by nature are already signed)
        if ( dependencies == null )
        {
            dependencies = new Dependencies();
        }

        if ( dependencies.getExcludes() == null )
        {
            dependencies.setExcludes( new ArrayList<String>() );
        }

        dependencies.getExcludes().addAll( includes );
    }

    /**
     * Iterate through all the extensions dependencies declared in the project and
     * collect all the runtime scope dependencies for inclusion in the .zip and just
     * copy them to the lib directory.
     * <p/>
     * TODO, should check that all dependencies are well signed with the same
     * extension with the same signer.
     *
     * @throws IOException
     * @throws MojoExecutionException
     */
    private void processExtensionsDependencies()
        throws IOException, MojoExecutionException
    {

        Collection<Artifact> artifacts =
            isExcludeTransitive() ? getProject().getDependencyArtifacts() : getProject().getArtifacts();

        for ( JnlpExtension extension : jnlpExtensions )
        {
            ArtifactFilter filter = new IncludesArtifactFilter( extension.getIncludes() );

            for ( Artifact artifact : artifacts )
            {
                if ( filter.include( artifact ) )
                {
                    processExtensionDependency( extension, artifact );
                }
            }
        }
    }

    private void processExtensionDependency( JnlpExtension extension, Artifact artifact )
        throws IOException, MojoExecutionException
    {
        // TODO: scope handler
        // Include runtime and compile time libraries
        if ( !Artifact.SCOPE_SYSTEM.equals( artifact.getScope() ) &&
            !Artifact.SCOPE_PROVIDED.equals( artifact.getScope() ) &&
            !Artifact.SCOPE_TEST.equals( artifact.getScope() ) )
        {
            String type = artifact.getType();
            if ( "jar".equals( type ) || "ejb-client".equals( type ) )
            {

                // FIXME when signed, we should update the manifest.
                // see http://www.mail-archive.com/turbine-maven-dev@jakarta.apache.org/msg08081.html
                // and maven1: maven-plugins/jnlp/src/main/org/apache/maven/jnlp/UpdateManifest.java
                // or shouldn't we?  See MOJO-7 comment end of October.
                final File toCopy = artifact.getFile();

                if ( toCopy == null )
                {
                    getLog().error( "artifact with no file: " + artifact );
                    getLog().error( "artifact download url: " + artifact.getDownloadUrl() );
                    getLog().error( "artifact repository: " + artifact.getRepository() );
                    getLog().error( "artifact repository: " + artifact.getVersion() );
                    throw new IllegalStateException(
                        "artifact " + artifact + " has no matching file, why? Check the logs..." );
                }

                // check jar is signed
                boolean jarSigned = isJarSigned( toCopy );
                if ( !jarSigned )
                {
                    throw new IllegalStateException(
                        "artifact " + artifact + " must be signed as part of an extension.." );
                }

                boolean copied = getIoUtil().copyFileToDirectoryIfNecessary( toCopy, getLibDirectory() );
                if ( copied )
                {
                    verboseLog( "copy extension artifact " + toCopy );
                }
                else
                {
                    verboseLog( "already up to date artifact " + toCopy );
                }

                // save the artifact dependency for the extension

                List<Artifact> deps = extensionsJnlpArtifacts.get( extension );
                if ( deps == null )
                {
                    deps = new ArrayList<Artifact>();
                    extensionsJnlpArtifacts.put( extension, deps );
                }
                deps.add( artifact );
            }
            else
            // FIXME how do we deal with native libs?
            // we should probably identify them and package inside jars that we timestamp like the native lib
            // to avoid repackaging every time. What are the types of the native libs?
            {
                verboseLog( "Skipping artifact of type " + type + " for " + getLibDirectory().getName() );
            }
            // END COPY
        }
        else
        {
            verboseLog( "Skipping artifact of scope " + artifact.getScope() + " for " + getLibDirectory().getName() );
        }
    }

    private void generateJnlpExtensionsFile( File outputDirectory )
        throws MojoExecutionException
    {
        for ( Object jnlpExtension : jnlpExtensions )
        {
            generateJnlpExtensionFile( outputDirectory, (JnlpExtension) jnlpExtension );
        }
    }

    private void generateJnlpExtensionFile( File outputDirectory, JnlpExtension extension )
        throws MojoExecutionException
    {

        File jnlpOutputFile = new File( outputDirectory, extension.getOutputFile() );

        File templateDirectory = getProject().getBasedir();

        if ( StringUtils.isNotBlank( extension.getInputTemplateResourcePath() ) )
        {
            templateDirectory = new File( extension.getInputTemplateResourcePath() );
        }

        if ( StringUtils.isBlank( extension.getInputTemplate() ) )
        {
            getLog().debug(
                "Jnlp extension template file name not specified. Checking if default output file name exists: " +
                    DEFAULT_TEMPLATE_LOCATION );

            File templateFile = new File( templateDirectory, DEFAULT_TEMPLATE_LOCATION );

            if ( templateFile.isFile() )
            {
                extension.setInputTemplate( DEFAULT_TEMPLATE_LOCATION );
            }
            else
            {
                getLog().debug( "Jnlp extension template file not found in default location. Using inbuilt one." );
            }
        }
        else
        {
            File templateFile = new File( templateDirectory, extension.getInputTemplate() );

            if ( !templateFile.isFile() )
            {
                throw new MojoExecutionException(
                    "The specified JNLP extension template does not exist: [" + templateFile + "]" );
            }
        }
        String templateFileName = extension.getInputTemplate();

        ExtensionGenerator jnlpGenerator =
            new ExtensionGenerator( getLog(), this.getProject(), this, extension, "default-jnlp-extension-template.vm",
                                    templateDirectory, jnlpOutputFile, templateFileName, this.getJnlp().getMainClass(),
                                    getWebstartJarURLForVelocity(), getEncoding() );

        jnlpGenerator.setExtraConfig( new ExtensionGeneratorExtraConfig( extension, getCodebase() ) );

        try
        {
            jnlpGenerator.generate();
        }
        catch ( Exception e )
        {
            getLog().debug( e.toString() );
            throw new MojoExecutionException( "Could not generate the JNLP deployment descriptor", e );
        }
    }

}

