/*******************************************************************************
 * Copyright (c) 2018 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package com.ibm.ws.session.cache.config.fat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.BufferedInputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Scanner;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.junit.After;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

import com.ibm.websphere.simplicity.ProgramOutput;
import com.ibm.websphere.simplicity.ShrinkHelper;
import com.ibm.websphere.simplicity.config.File;
import com.ibm.websphere.simplicity.config.Library;
import com.ibm.websphere.simplicity.config.ServerConfiguration;

import componenttest.annotation.AllowedFFDC;
import componenttest.annotation.Server;
import componenttest.custom.junit.runner.FATRunner;
import componenttest.topology.impl.LibertyServer;
import componenttest.topology.utils.FATServletClient;

@RunWith(FATRunner.class)
public class SessionCacheErrorPathsTest extends FATServletClient {

    private static final String APP_NAME = "sessionCacheConfigApp";
    private static final Set<String> APP_NAMES = Collections.singleton(APP_NAME);
    private static final String[] EMPTY_RECYCLE_LIST = new String[0];
    private static final String SERVLET_NAME = "SessionCacheConfigTestServlet";

    private static ServerConfiguration savedConfig;

    @Server("sessionCacheServer")
    public static LibertyServer server;

    /**
     * After running each test, ensure the server is stopped and restore the original configuration.
     */
    @After
    public void cleanUpPerTest() throws Exception {
        try {
            if (server.isStarted())
                server.stopServer();
        } finally {
            server.updateServerConfiguration(savedConfig);
        }
        System.out.println("server configuration restored");
    }

    @BeforeClass
    public static void setUp() throws Exception {
        ShrinkHelper.defaultApp(server, APP_NAME, "session.cache.web");

        String configLocation = new java.io.File(server.getUserDir() + "/shared/resources/hazelcast/hazelcast-localhost-only.xml").getAbsolutePath();
        server.setJvmOptions(Arrays.asList("-Dhazelcast.config=" + configLocation,
                                           "-Dhazelcast.group.name=" + UUID.randomUUID()));

        savedConfig = server.getServerConfiguration().clone();
    }

    /**
     * Utility method to dump the server and collect the session cache introspector output.
     *
     * @return list of lines of the session cache introspector output.
     */
    private List<String> sessionCacheIntrospectorDump() throws Exception {
        ProgramOutput output = server.serverDump();
        assertEquals(0, output.getReturnCode());
        assertEquals("", output.getStderr());

        // Parse standard output. Example:
        // Server sessionCacheServer dump complete in /Users/user/lgit/open-liberty/dev/build.image/wlp/usr/servers/sessionCacheServer/sessionCacheServer.dump-18.04.11_14.30.55.zip.

        String out = output.getStdout();
        int end = out.lastIndexOf('.');
        int begin = out.lastIndexOf(' ', end) + 1;

        String dumpFileName = out.substring(begin, end);

        System.out.println("Dump file name: " + dumpFileName);

        // Example of file within the zip:
        // dump_18.04.11_14.30.55/introspections/SessionCacheIntrospector.txt

        end = dumpFileName.indexOf(".zip");
        begin = dumpFileName.lastIndexOf("/sessionCacheServer.dump-", end) + 25;

        String introspectorFileName = "dump_" + dumpFileName.substring(begin, end) + "/introspections/SessionCacheIntrospector.txt";

        System.out.println("Looking for intropspector entry: " + introspectorFileName);

        List<String> lines = new ArrayList<String>();
        try (ZipFile dumpFile = new ZipFile(dumpFileName)) {
            ZipEntry entry = dumpFile.getEntry(introspectorFileName);
            System.out.println("Found: " + entry);
            try (BufferedInputStream in = new BufferedInputStream(dumpFile.getInputStream(entry))) {
                for (Scanner scanner = new Scanner(in); scanner.hasNextLine();) {
                    String line = scanner.nextLine();
                    System.out.println(line);
                    lines.add(line);
                }
            }
        }

        return lines;
    }

    /**
     * Remove and add the sessionCache-1.0 feature while the server is running. Access a session before and after,
     * verifying that a session attribute added afterward is persisted, whereas a session attribute added before
     * (in absence of sessionCache-1.0 feature) is not.
     */
    @AllowedFFDC("java.net.MalformedURLException")
    @Test
    public void testAddFeature() throws Exception {
        // Start the server with sessionCache-1.0 enabled
        server.startServer(testName.getMethodName() + ".log");

        // Access a session and add an attribute
        List<String> session = new ArrayList<>();
        FATSuite.run(server, APP_NAME + '/' + SERVLET_NAME, "testSetAttribute&attribute=testAddFeature0&value=AF0", session);

        // Disable sessionCache-1.0
        ServerConfiguration config = savedConfig.clone();
        config.getFeatureManager().getFeatures().remove("sessionCache-1.0");
        // After discussing with the classloading team, due to the bundle which contains the jcache spec also being
        // restarted during the feature change, the library ends up with out-of-date references to the class loader.
        // To work around this limitation, the library also needs to be recycled.
        Library hazelcastLib = config.getLibraries().remove(0);
        server.setMarkToEndOfLog();
        server.updateServerConfiguration(config);
        server.waitForConfigUpdateInLogUsingMark(APP_NAMES, EMPTY_RECYCLE_LIST);

        // Session manager should warn user that sessions will be stored in memory
        assertEquals(1, server.findStringsInLogs("SESN8501I").size());

        FATSuite.run(server, APP_NAME + '/' + SERVLET_NAME, "testSetAttributeOnly&attribute=testAddFeature1&value=AF1", session);

        // Add the sessionCache-1.0 feature
        config.getFeatureManager().getFeatures().add("sessionCache-1.0");
        config.getLibraries().add(hazelcastLib);
        server.setMarkToEndOfLog();
        server.updateServerConfiguration(config);
        server.waitForConfigUpdateInLogUsingMark(APP_NAMES, EMPTY_RECYCLE_LIST);

        FATSuite.run(server, APP_NAME + '/' + SERVLET_NAME, "testSetAttribute&attribute=testAddFeature2&value=AF2", session);

        // second value should be written to the cache
        FATSuite.run(server, APP_NAME + '/' + SERVLET_NAME, "testCacheContains&attribute=testAddFeature2&value=AF2", session);

        // first value should not be written to the cache
        FATSuite.run(server, APP_NAME + '/' + SERVLET_NAME, "testCacheContains&attribute=testAddFeature1&value=null", session);

        FATSuite.run(server, APP_NAME + '/' + SERVLET_NAME, "invalidateSession", session);
    }

    /**
     * Start the server with an invalid Hazelcast uri configured on httpSessionCache.
     * Verify that after correcting the uri, session data is persisted.
     */
    @AllowedFFDC(value = { "javax.cache.CacheException", // for invalid uri
                           "java.lang.NullPointerException", // from starting web app with invalid session cache config
                           "java.net.MalformedURLException" // TODO possible bug
    })
    @Test
    public void testInvalidURI() throws Exception {
        // Start the server with invalid httpSessionCache uri
        String invalidHazelcastURI = "file:" + new java.io.File(server.getUserDir() + "/servers/sessionCacheServer/server.xml").getAbsolutePath();
        ServerConfiguration config = savedConfig.clone();
        config.getHttpSessionCaches().get(0).setUri(invalidHazelcastURI);
        server.updateServerConfiguration(config);
        server.startServer(testName.getMethodName() + ".log");

        try {
            List<String> session = new ArrayList<>();
            FATSuite.run(server, APP_NAME + '/' + SERVLET_NAME, "testSessionCacheNotAvailable", session);

            // Correct the URI
            String validHazelcastURI = "file:" + new java.io.File(server.getUserDir() + "/shared/resources/hazelcast/hazelcast-localhost-only.xml").getAbsolutePath();
            config.getHttpSessionCaches().get(0).setUri(validHazelcastURI);
            server.setMarkToEndOfLog();
            server.updateServerConfiguration(config);
            server.waitForConfigUpdateInLogUsingMark(APP_NAMES, EMPTY_RECYCLE_LIST);

            session = new ArrayList<>();
            FATSuite.run(server, APP_NAME + '/' + SERVLET_NAME, "testSetAttribute&attribute=testInvalidURI2&value=IU2", session);

            // value should be found
            FATSuite.run(server, APP_NAME + '/' + SERVLET_NAME, "testCacheContains&useURI=true&attribute=testInvalidURI2&value=IU2", session);

            // Remove the URI and let it default to the system property
            server.setMarkToEndOfLog();
            server.updateServerConfiguration(savedConfig);
            server.waitForConfigUpdateInLogUsingMark(APP_NAMES, EMPTY_RECYCLE_LIST);

            FATSuite.run(server, APP_NAME + '/' + SERVLET_NAME, "testSetAttribute&attribute=testInvalidURI3&value=IU3", session);

            // second value should be written to the cache
            FATSuite.run(server, APP_NAME + '/' + SERVLET_NAME, "testCacheContains&useURI=false&attribute=testInvalidURI3&value=IU3", session);

            // whether or not the first value is written depends on JCache provider's behavior when same URI specified a different way

            FATSuite.run(server, APP_NAME + '/' + SERVLET_NAME, "invalidateSession", session);
        } finally {
            server.stopServer("SRVE8059E", // An unexpected exception occurred when trying to retrieve the session context. Caused by: java.lang.IllegalArgumentException: Not available Hazelcast instance...
                              "SRVE0297E.*NullPointerException" // TODO fails when WebApp.destroy/SessionContext.stop invoked after deactivate
            );
        }
    }

    /**
     * Configure httpSessionCache pointing at a library that lacks a valid JCache provider. Access a session before and after,
     * verifying that a session attribute added afterward is persisted, whereas a session attribute added before is not.
     */
    @AllowedFFDC(value = { "java.lang.NullPointerException", // from starting web app with invalid session cache config
                           "java.net.MalformedURLException" // TODO possible bug
    })
    @Test
    public void testLibraryWithoutJCacheProvider() throws Exception {
        // Start the server with libraryRef missing
        ServerConfiguration config = savedConfig.clone();
        Library libraryWithoutJCacheProvider = new Library();
        libraryWithoutJCacheProvider.setId("libraryWithoutJCacheProvider");
        File libraryWithoutJCacheProvider_file = new File();
        // specify a binary already produced by the FAT bucket that won't have a JCache provider in it
        libraryWithoutJCacheProvider_file.setName("${server.config.dir}/apps/sessionCacheConfigApp.war");
        libraryWithoutJCacheProvider.setFile(libraryWithoutJCacheProvider_file);
        config.getLibraries().add(libraryWithoutJCacheProvider);
        config.getHttpSessionCaches().get(0).setLibraryRef("libraryWithoutJCacheProvider");
        server.updateServerConfiguration(config);
        server.startServer(testName.getMethodName() + ".log");
        try {
            List<String> session = new ArrayList<>();
            FATSuite.run(server, APP_NAME + '/' + SERVLET_NAME, "testSessionCacheNotAvailable", session);

            // Correct the libraryRef to point at Hazelcast JCache provider
            server.setMarkToEndOfLog();
            server.updateServerConfiguration(savedConfig);
            server.waitForConfigUpdateInLogUsingMark(APP_NAMES, EMPTY_RECYCLE_LIST);

            session = new ArrayList<>();
            FATSuite.run(server, APP_NAME + '/' + SERVLET_NAME, "testSetAttribute&attribute=testLibraryWithoutJCacheProvider2&value=LWJCP2", session);

            // value should be written to the cache
            FATSuite.run(server, APP_NAME + '/' + SERVLET_NAME, "testCacheContains&attribute=testLibraryWithoutJCacheProvider2&value=LWJCP2", session);

            FATSuite.run(server, APP_NAME + '/' + SERVLET_NAME, "invalidateSession", session);
        } finally {
            server.stopServer("SRVE8059E"); // An unexpected exception occurred when trying to retrieve the session context. javax.cache.CacheException: No CachingProviders have been configured
        }
    }

    /**
     * Configure httpSessionCache lacking a libraryRef (or bell). Access a session before and after,
     * verifying that a session attribute added afterward is persisted, whereas a session attribute added before is not
     * (the OSGi service backing httpSessionCache config will be unable to activate in the absence of libraryRef).
     */
    @AllowedFFDC("java.net.MalformedURLException") // TODO possible bug
    @Test
    public void testMissingLibraryRef() throws Exception {
        // Start the server with libraryRef missing
        ServerConfiguration config = savedConfig.clone();
        config.getHttpSessionCaches().get(0).setLibraryRef(null);
        server.updateServerConfiguration(config);
        server.startServer(testName.getMethodName() + ".log");

        // Session manager should warn user that sessions will be stored in memory
        assertEquals(1, server.findStringsInLogs("SESN8501I").size());

        List<String> session = new ArrayList<>();
        FATSuite.run(server, APP_NAME + '/' + SERVLET_NAME, "testSetAttribute&attribute=testMissingLibraryRef1&value=MLF1", session);

        // Add the libraryRef
        server.setMarkToEndOfLog();
        server.updateServerConfiguration(savedConfig);
        server.waitForConfigUpdateInLogUsingMark(APP_NAMES, EMPTY_RECYCLE_LIST);

        FATSuite.run(server, APP_NAME + '/' + SERVLET_NAME, "testSetAttribute&attribute=testMissingLibraryRef2&value=MLF2", session);

        // second value should be written to the cache
        FATSuite.run(server, APP_NAME + '/' + SERVLET_NAME, "testCacheContains&attribute=testMissingLibraryRef2&value=MLF2", session);

        // first value should not be written to the cache
        FATSuite.run(server, APP_NAME + '/' + SERVLET_NAME, "testCacheContains&attribute=testMissingLibraryRef1&value=null", session);

        FATSuite.run(server, APP_NAME + '/' + SERVLET_NAME, "invalidateSession", session);
    }

    @AllowedFFDC(value = { "java.lang.NullPointerException", // from starting web app with invalid session cache config
                           "java.net.MalformedURLException" // TODO possible bug
    })
    @Test
    public void testModifyFileset() throws Exception {
        ServerConfiguration config = server.getServerConfiguration();
        com.ibm.websphere.simplicity.config.File hazelcastFile = config.getLibraries().getById("HazelcastLib").getNestedFile();
        String originalName = hazelcastFile.getName();
        server.startServer(testName.getMethodName() + ".log");

        // Use sessionCache with original (good) config
        List<String> session = new ArrayList<>();
        FATSuite.run(server, APP_NAME + '/' + SERVLET_NAME, "testSetAttribute&attribute=testModifyFileset&value=0", session);
        FATSuite.run(server, APP_NAME + '/' + SERVLET_NAME, "testCacheContains&attribute=testModifyFileset&value=0", session);

        // Change the fileset to reference a bogus file
        hazelcastFile.setName("${shared.resource.dir}/hazelcast/bogus.jar");
        server.setMarkToEndOfLog();
        server.updateServerConfiguration(config);
        server.waitForConfigUpdateInLogUsingMark(APP_NAMES);

        FATSuite.run(server, APP_NAME + '/' + SERVLET_NAME, "testSessionCacheNotAvailable", session);

        // Restore the original config and expect the new cache to be usable
        hazelcastFile.setName(originalName);
        server.setMarkToEndOfLog();
        server.updateServerConfiguration(config);
        server.waitForConfigUpdateInLogUsingMark(APP_NAMES);
        FATSuite.run(server, APP_NAME + '/' + SERVLET_NAME, "testSetAttribute&attribute=testModifyFileset&value=1", session);
        FATSuite.run(server, APP_NAME + '/' + SERVLET_NAME, "testCacheContains&attribute=testModifyFileset&value=1", session);

        server.stopServer("CWWKL0012W.*bogus", "SRVE8059E", "CWWKE0701E.*CacheException");
    }

    /**
     * Capture a dump of the server without monitoring enabled. This means the JCache MXBeans will be unavailable
     * and so cache statistics will not be included in the dump output.
     */
    @AllowedFFDC("java.net.MalformedURLException") // TODO possible bug
    @Test
    public void testServerDumpWithoutMonitoring() throws Exception {
        server.startServer();

        // access a session to exercise codepath before capturing dump
        List<String> session1 = new ArrayList<>();
        List<String> session2 = new ArrayList<>();
        FATSuite.run(server, APP_NAME + '/' + SERVLET_NAME, "testSetAttributeWithTimeout&attribute=testServerDumpWithoutMonitoring1&value=val1&maxInactiveInterval=1900", session1);
        try {
            FATSuite.run(server, APP_NAME + '/' + SERVLET_NAME, "testSetAttributeWithTimeout&attribute=testServerDumpWithoutMonitoring2&value=val2&maxInactiveInterval=2000",
                         session2);

            List<String> lines = sessionCacheIntrospectorDump();
            String dumpInfo = lines.toString();
            int i = 0;

            assertTrue(dumpInfo, lines.contains("JCache provider diagnostics for HTTP Sessions"));
            assertTrue(dumpInfo, lines.contains("CachingProvider implementation: com.hazelcast.cache.HazelcastCachingProvider"));
            assertTrue(dumpInfo, lines.contains("Cache manager URI: hazelcast"));
            assertTrue(dumpInfo, lines.contains("Cache manager is closed? false"));
            assertFalse(dumpInfo, lines.contains("Cache manager is closed? true"));

            assertTrue(dumpInfo, (i = lines.indexOf("Cache names:")) > 0);

            Set<String> expectedCaches = new HashSet<String>();
            expectedCaches.add("com.ibm.ws.session.meta.default_host%2FsessionCacheConfigApp");
            expectedCaches.add("com.ibm.ws.session.attr.default_host%2FsessionCacheConfigApp");
            Set<String> caches = new HashSet<String>();
            for (int c = i + 1; c < lines.size() && lines.get(c).startsWith("  "); c++) // add all subsequent indented lines
                caches.add(lines.get(c).trim());
            assertEquals(dumpInfo, expectedCaches, caches);

            assertTrue(dumpInfo, lines.contains("  closed? false"));
            assertFalse(dumpInfo, lines.contains("  closed? true"));

            assertTrue(dumpInfo, lines.contains("  is management enabled? false"));
            assertFalse(dumpInfo, lines.contains("  is management enabled? true"));

            assertTrue(dumpInfo, lines.contains("  is statistics enabled? false"));
            assertFalse(dumpInfo, lines.contains("  is statistics enabled? true"));

            assertTrue(dumpInfo, lines.parallelStream().anyMatch(s -> s
                            .matches("    session \\S+: SessionInfo for anonymous created \\d+ accessed \\d+ listeners 0 maxInactive 1900 \\[testServerDumpWithoutMonitoring1\\]")));

            assertTrue(dumpInfo, lines.parallelStream().anyMatch(s -> s
                            .matches("    session \\S+: SessionInfo for anonymous created \\d+ accessed \\d+ listeners 0 maxInactive 2000 \\[testServerDumpWithoutMonitoring2\\]")));
        } finally {
            FATSuite.run(server, APP_NAME + '/' + SERVLET_NAME, "invalidateSession", session1);
            FATSuite.run(server, APP_NAME + '/' + SERVLET_NAME, "invalidateSession", session2);
        }
    }
}
