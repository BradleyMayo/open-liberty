/*******************************************************************************
 * Copyright (c) 2017,2019 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package com.ibm.ws.rest.handler.validator.fat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import javax.json.JsonArray;
import javax.json.JsonObject;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

import componenttest.annotation.ExpectedFFDC;
import componenttest.annotation.Server;
import componenttest.custom.junit.runner.FATRunner;
import componenttest.topology.impl.LibertyServer;
import componenttest.topology.utils.FATServletClient;
import componenttest.topology.utils.HttpsRequest;

@RunWith(FATRunner.class)
public class ValidateDataSourceTest extends FATServletClient {

    @Server("com.ibm.ws.rest.handler.validator.jdbc.fat")
    public static LibertyServer server;

    private static String VERSION_REGEX = "[0-9]+\\.[0-9]+.*";

    @BeforeClass
    public static void setUp() throws Exception {
        server.startServer();

        // Wait for the API to become available
        assertNotNull(server.waitForStringInLog("CWWKS0008I")); // CWWKS0008I: The security service is ready.
        assertNotNull(server.waitForStringInLog("CWWKS4105I")); // CWWKS4105I: LTPA configuration is ready after # seconds.
        assertNotNull(server.waitForStringInLog("CWPKI0803A")); // CWPKI0803A: SSL certificate created in # seconds. SSL key file: ...
        assertNotNull(server.waitForStringInLog("CWWKO0219I")); // CWWKO0219I: TCP Channel defaultHttpEndpoint-ssl has been started and is now listening for requests on host *  (IPv6) port 8020.
        assertNotNull(server.waitForStringInLog("CWWKT0016I")); // CWWKT0016I: Web application available (default_host): http://9.10.111.222:8010/ibm/api/

        // TODO remove once transactions code is fixed to use container auth for the recovery log dataSource
        // Lacking this fix, transaction manager will experience an auth failure and log FFDC for it.
        // The following line causes an XA-capable data source to be used for the first time outside of a test method execution,
        // so that the FFDC is not considered a test failure.
        new HttpsRequest(server, "/ibm/api/validator/dataSource/DefaultDataSource").run(JsonObject.class);
    }

    @AfterClass
    public static void tearDown() throws Exception {
        server.stopServer("CWWKE0701E", // TODO remove once transaction manager fixes its circular reference bug
                          "CWWKS1300E", // auth alias doesn't exist
                          "WTRN0112E" // TODO remove once transactions code is fixed to use container auth for the recovery log dataSource
        );
    }

    @Test
    public void testAppAuth() throws Exception {
        HttpsRequest request = new HttpsRequest(server, "/ibm/api/validator/dataSource/DefaultDataSource")
                        .requestProp("X-Validator-User", "dbuser")
                        .requestProp("X-Validator-Password", "dbpass");
        JsonObject json = request.run(JsonObject.class);
        String err = "Unexpected json response: " + json.toString();
        assertEquals(err, "DefaultDataSource", json.getString("uid"));
        assertEquals(err, "DefaultDataSource", json.getString("id"));
        assertNull(err, json.get("jndiName"));
        assertTrue(err, json.getBoolean("successful"));
        assertNull(err, json.get("failure"));
        assertNotNull(err, json = json.getJsonObject("info"));
        assertEquals(err, "Apache Derby", json.getString("databaseProductName"));
        assertTrue(err, json.getString("databaseProductVersion").matches(VERSION_REGEX));
        assertEquals(err, "Apache Derby Embedded JDBC Driver", json.getString("jdbcDriverName"));
        assertTrue(err, json.getString("jdbcDriverVersion").matches(VERSION_REGEX));

        request.method("POST");
        json = request.run(JsonObject.class);
        err = "Unexpected json response: " + json.toString();
        assertEquals(err, "DefaultDataSource", json.getString("uid"));
        assertEquals(err, "DefaultDataSource", json.getString("id"));
        assertNull(err, json.get("jndiName"));
        assertTrue(err, json.getBoolean("successful"));
        assertNull(err, json.get("failure"));
        assertNotNull(err, json = json.getJsonObject("info"));
        assertEquals(err, "Apache Derby", json.getString("databaseProductName"));
        assertTrue(err, json.getString("databaseProductVersion").matches(VERSION_REGEX));
        assertEquals(err, "Apache Derby Embedded JDBC Driver", json.getString("jdbcDriverName"));
        assertTrue(err, json.getString("jdbcDriverVersion").matches(VERSION_REGEX));
    }

    @Test
    @ExpectedFFDC({ "java.sql.SQLNonTransientConnectionException",
                    "java.sql.SQLNonTransientException",
                    "javax.resource.spi.SecurityException",
                    "javax.resource.spi.ResourceAllocationException" })
    public void testAppAuthFails() throws Exception {
        JsonObject json = new HttpsRequest(server, "/ibm/api/validator/dataSource/DefaultDataSource")
                        .requestProp("X-Validator-User", "bogus")
                        .requestProp("X-Validator-Password", "bogus")
                        .run(JsonObject.class);
        String err = "unexpected response: " + json;
        assertEquals(err, "DefaultDataSource", json.getString("uid"));
        assertEquals(err, "DefaultDataSource", json.getString("id"));
        assertFalse(err, json.getBoolean("successful"));
        assertNull(err, json.get("info"));
        assertNull(err, json.get("cause"));

        // Now examine the failure object
        json = json.getJsonObject("failure");
        assertNotNull(err, json);
        assertNull(err, json.get("uid"));
        assertNull(err, json.get("id"));
        assertNull(err, json.get("jndiName"));
        assertNull(err, json.get("failure"));
        assertNull(err, json.get("info"));
        assertEquals(err, "08004", json.getString("sqlState"));
        assertEquals(err, 40000, json.getInt("errorCode"));
        assertEquals(err, "java.sql.SQLNonTransientException", json.getString("class"));
        assertTrue(err, json.getString("message").contains("Invalid authentication"));
    }

    @Test
    public void testDataSourceWithoutJDBCDriver() throws Exception {
        JsonObject json = new HttpsRequest(server, "/ibm/api/validator/dataSource/DataSourceWithoutJDBCDriver").run(JsonObject.class);
        String err = "unexpected response: " + json;
        assertEquals(err, "DataSourceWithoutJDBCDriver", json.getString("uid"));
        assertEquals(err, "DataSourceWithoutJDBCDriver", json.getString("id"));
        assertEquals(err, "jdbc/withoutJDBCDriver", json.getString("jndiName"));
        assertFalse(err, json.getBoolean("successful"));
        assertNull(err, json.get("info"));
        assertNotNull(err, json = json.getJsonObject("failure"));
        assertTrue(err, json.getString("message").contains("dependencies"));
    }

    @Test
    public void testDefaultAuth() throws Exception {
        JsonObject json = new HttpsRequest(server, "/ibm/api/validator/dataSource/dataSource[default-0]?auth=container").run(JsonObject.class);
        String err = "Unexpected json response: " + json;
        assertEquals(err, "dataSource[default-0]", json.getString("uid"));
        assertNull(err, json.get("id"));
        assertEquals(err, "jdbc/defaultauth", json.getString("jndiName"));
        assertTrue(err, json.getBoolean("successful"));
        assertNull(err, json.get("failure"));
        assertNotNull(err, json = json.getJsonObject("info"));
        assertEquals(err, "Apache Derby", json.getString("databaseProductName"));
        assertTrue(err, json.getString("databaseProductVersion").matches(VERSION_REGEX));
        assertEquals(err, "Apache Derby Embedded JDBC Driver", json.getString("jdbcDriverName"));
        assertTrue(err, json.getString("jdbcDriverVersion").matches(VERSION_REGEX));
    }

    @Test
    public void testFeatureOfParentConfigNotEnabled() throws Exception {
        JsonObject json = new HttpsRequest(server, "/ibm/api/validator/dataSource/databaseStore[unavailableDBStore]%2FdataSource[unavailableDS]").run(JsonObject.class);
        String err = "unexpected response: " + json;
        assertEquals(err, "databaseStore[unavailableDBStore]/dataSource[unavailableDS]", json.getString("uid"));
        assertFalse(err, json.getBoolean("successful"));
        assertNull(err, json.get("info"));
        // Is there any way to know that this configuration is unavailable due to being nested under a config element of a feature that is not enabled?
        assertNotNull(err, json = json.getJsonObject("failure"));
        assertTrue(err, json.getString("message").contains("Did not find any configured instances of dataSource matching the request")); // TODO: "feature"));
    }

    @Test
    public void testFeatureNotEnabled() throws Exception {
        JsonObject json = new HttpsRequest(server, "/ibm/api/validator/cloudantDatabase/CloudantDBNotEnabled").run(JsonObject.class);
        String err = "unexpected response: " + json;
        assertEquals(err, "CloudantDBNotEnabled", json.getString("uid"));
        assertEquals(err, "CloudantDBNotEnabled", json.getString("id"));
        assertEquals(err, "cloudant/db", json.getString("jndiName"));
        assertFalse(err, json.getBoolean("successful"));
        assertNull(err, json.get("info"));
        assertNotNull(err, json = json.getJsonObject("failure"));
        assertTrue(err, json.getString("message").contains("feature"));
    }

    @Test
    @ExpectedFFDC(value = { "java.sql.SQLException",
                            "javax.resource.spi.ResourceAllocationException",
                            "com.ibm.ws.rsadapter.exceptions.DataStoreAdapterException" })
    public void testMultiple() throws Exception {
        HttpsRequest request = new HttpsRequest(server, "/ibm/api/validator/dataSource?auth=application")
                        .requestProp("X-Validator-User", "dbuser")
                        .requestProp("X-Validator-Password", "dbpass");
        JsonArray json = request.method("POST").run(JsonArray.class);
        String err = "unexpected response: " + json;

        assertEquals(err, 6, json.size()); // Increase this if you add more data sources to server.xml

        // Order is currently alphabetical based on config.displayId

        // [0]: config.displayId=dataSource[DataSourceWithoutJDBCDriver]
        JsonObject j = json.getJsonObject(0);
        assertEquals(err, "DataSourceWithoutJDBCDriver", j.getString("uid"));
        assertEquals(err, "DataSourceWithoutJDBCDriver", j.getString("id"));
        assertEquals(err, "jdbc/withoutJDBCDriver", j.getString("jndiName"));
        assertFalse(err, j.getBoolean("successful"));
        assertNull(err, j.get("info"));
        assertNotNull(err, j = j.getJsonObject("failure"));
        assertTrue(err, j.getString("message").contains("dependencies"));

        // [1]: config.displayId=dataSource[DefaultDataSource]
        j = json.getJsonObject(1);
        assertEquals(err, "DefaultDataSource", j.getString("uid"));
        assertEquals(err, "DefaultDataSource", j.getString("id"));
        assertNull(err, j.get("jndiName"));
        assertTrue(err, j.getBoolean("successful"));
        assertNull(err, j.get("failure"));
        assertNotNull(err, j = j.getJsonObject("info"));
        assertEquals(err, "Apache Derby", j.getString("databaseProductName"));
        assertTrue(err, j.getString("databaseProductVersion").matches(VERSION_REGEX));
        assertNull(err, j.get("catalog")); // currently not supported by Derby
        assertEquals(err, "DBUSER", j.getString("schema"));
        assertEquals(err, "dbuser", j.getString("user"));

        // [2]: config.displayId=dataSource[WrongDefaultAuth]
        j = json.getJsonObject(2);
        assertEquals(err, "WrongDefaultAuth", j.getString("uid"));
        assertEquals(err, "WrongDefaultAuth", j.getString("id"));
        assertEquals(err, "jdbc/wrongdefaultauth", j.getString("jndiName"));
        assertTrue(err, j.getBoolean("successful"));
        assertNull(err, j.get("failure"));
        assertNotNull(err, j = j.getJsonObject("info"));
        assertEquals(err, "Apache Derby Embedded JDBC Driver", j.getString("jdbcDriverName"));
        assertTrue(err, j.getString("jdbcDriverVersion").matches(VERSION_REGEX));

        // [3]: config.displayId=dataSource[default-0]
        j = json.getJsonObject(3);
        assertEquals(err, "dataSource[default-0]", j.getString("uid"));
        assertNull(err, j.get("id"));
        assertEquals(err, "jdbc/defaultauth", j.getString("jndiName"));
        assertTrue(err, j.getBoolean("successful"));
        assertNull(err, j.get("failure"));
        assertNotNull(err, j = j.getJsonObject("info"));

        // [4]: config.displayId=dataSource[jdbc/nonexistentdb]
        j = json.getJsonObject(4);
        assertEquals(err, "jdbc/nonexistentdb", j.getString("uid"));
        assertEquals(err, "jdbc/nonexistentdb", j.getString("id"));
        assertEquals(err, "jdbc/nonexistentdb", j.getString("jndiName"));
        assertFalse(err, j.getBoolean("successful"));
        assertNull(err, j.get("info"));
        assertNotNull(err, j = j.getJsonObject("failure"));
        assertEquals(err, "XJ004", j.getString("sqlState"));
        assertEquals(err, 40000, j.getInt("errorCode"));
        assertEquals(err, "java.sql.SQLException", j.getString("class"));
        assertTrue(err, j.getString("message").contains("memory:doesNotExist"));
        JsonArray stack = j.getJsonArray("stack");
        assertNotNull(err, stack);
        assertTrue(err, stack.size() > 10); // stack is actually much longer, but size could vary
        assertTrue(err, stack.getString(0).startsWith("org.apache.derby."));
        assertTrue(err, stack.getString(1).startsWith("org.apache.derby."));
        assertTrue(err, stack.getString(2).startsWith("org.apache.derby."));
        assertNotNull(err, j = j.getJsonObject("cause"));
        assertEquals(err, "org.apache.derby.iapi.error.StandardException", j.getString("class"));
        assertTrue(err, j.getString("message").contains("memory:doesNotExist"));
        stack = j.getJsonArray("stack");
        assertNotNull(err, stack);
        assertTrue(err, stack.size() > 10); // stack is actually much longer, but size could vary
        assertTrue(err, stack.getString(0).startsWith("org.apache.derby."));
        assertTrue(err, stack.getString(1).startsWith("org.apache.derby."));
        assertTrue(err, stack.getString(2).startsWith("org.apache.derby."));

        // [5]: config.displayId=transaction/dataSource[default-0]
        j = json.getJsonObject(5);
        assertEquals(err, "transaction/dataSource[default-0]", j.getString("uid"));
        assertNull(err, j.get("id"));
        assertNull(err, j.get("jndiName"));
        assertTrue(err, j.getBoolean("successful"));
        assertNull(err, j.get("failure"));
        assertNotNull(err, j = j.getJsonObject("info"));
        assertEquals(err, "Apache Derby", j.getString("databaseProductName"));
        assertTrue(err, j.getString("databaseProductVersion").matches(VERSION_REGEX));
        assertEquals(err, "Apache Derby Embedded JDBC Driver", j.getString("jdbcDriverName"));
        assertTrue(err, j.getString("jdbcDriverVersion").matches(VERSION_REGEX));
        assertNull(err, j.get("catalog")); // currently not supported by Derby
        assertEquals(err, "DBUSER", j.getString("schema"));
        assertEquals(err, "dbuser", j.getString("user"));
    }

    @Test
    public void testMultipleWithNoResults() throws Exception {
        JsonArray json = new HttpsRequest(server, "/ibm/api/validator/mongoDB").run(JsonArray.class);

        assertEquals("unexpected response: " + json, 0, json.size());
    }

    @Test
    public void testNestedUnderTransaction() throws Exception {
        JsonObject json = new HttpsRequest(server, "/ibm/api/validator/dataSource/transaction%2FdataSource[default-0]?auth=container").run(JsonObject.class);
        String err = "unexpected response: " + json;
        assertEquals(err, "transaction/dataSource[default-0]", json.getString("uid"));
        assertNull(err, json.get("id"));
        assertNull(err, json.get("jndiName"));
        assertTrue(err, json.getBoolean("successful"));
        assertNull(err, json.get("failure"));
        assertNotNull(err, json = json.getJsonObject("info"));
        assertEquals(err, "Apache Derby", json.getString("databaseProductName"));
        assertTrue(err, json.getString("databaseProductVersion").matches(VERSION_REGEX));
        assertEquals(err, "Apache Derby Embedded JDBC Driver", json.getString("jdbcDriverName"));
        assertTrue(err, json.getString("jdbcDriverVersion").matches(VERSION_REGEX));
    }

    @Test
    public void testNotFound() throws Exception {
        JsonObject json = new HttpsRequest(server, "/ibm/api/validator/dataSource/NotAConfiguredDataSource").run(JsonObject.class);
        String err = "unexpected response: " + json;
        assertEquals(err, "NotAConfiguredDataSource", json.getString("uid"));
        assertNull(err, json.get("id"));
        assertNull(err, json.get("jndiName"));
        assertFalse(err, json.getBoolean("successful"));
        assertNull(err, json.get("info"));
        assertNotNull(err, json = json.getJsonObject("failure"));
        assertTrue(err, json.getString("message").contains("Did not find any configured instances of dataSource matching the request"));
    }

    @Test
    public void testNotValidatable() throws Exception {
        JsonObject json = new HttpsRequest(server, "/ibm/api/validator/library/Derby").run(JsonObject.class);
        String err = "unexpected response: " + json;
        assertEquals(err, "Derby", json.getString("uid"));
        assertEquals(err, "Derby", json.getString("id"));
        assertFalse(err, json.getBoolean("successful"));
        assertNull(err, json.get("info"));
        assertNotNull(err, json = json.getJsonObject("failure"));
        assertTrue(err, json.getString("message").contains("not possible to validate this type of resource"));
    }

    @Test
    public void testProvidedAuth() throws Exception {
        JsonObject json = new HttpsRequest(server, "/ibm/api/validator/dataSource/DefaultDataSource?auth=container&authAlias=auth1").run(JsonObject.class);
        String err = "Unexpected json response: " + json;
        assertEquals(err, "DefaultDataSource", json.getString("uid"));
        assertEquals(err, "DefaultDataSource", json.getString("id"));
        assertNull(err, json.get("jndiName"));
        assertTrue(err, json.getBoolean("successful"));
        assertNull(err, json.get("failure"));
        assertNotNull(err, json = json.getJsonObject("info"));
        assertEquals(err, "Apache Derby", json.getString("databaseProductName"));
        assertTrue(err, json.getString("databaseProductVersion").matches(VERSION_REGEX));
        assertEquals(err, "Apache Derby Embedded JDBC Driver", json.getString("jdbcDriverName"));
        assertTrue(err, json.getString("jdbcDriverVersion").matches(VERSION_REGEX));
    }

    @Test
    public void testProvidedAuthAndDefaultAuth() throws Exception {
        JsonObject json = new HttpsRequest(server, "/ibm/api/validator/dataSource/WrongDefaultAuth?auth=container&authAlias=auth1").run(JsonObject.class);
        String err = "Unexpected json response: " + json;
        assertEquals(err, "WrongDefaultAuth", json.getString("uid"));
        assertEquals(err, "WrongDefaultAuth", json.getString("id"));
        assertEquals(err, "jdbc/wrongdefaultauth", json.getString("jndiName"));
        assertTrue(err, json.getBoolean("successful"));
        assertNull(err, json.get("failure"));
        assertNotNull(err, json = json.getJsonObject("info"));
        assertEquals(err, "Apache Derby", json.getString("databaseProductName"));
        assertTrue(err, json.getString("databaseProductVersion").matches(VERSION_REGEX));
        assertEquals(err, "Apache Derby Embedded JDBC Driver", json.getString("jdbcDriverName"));
        assertTrue(err, json.getString("jdbcDriverVersion").matches(VERSION_REGEX));
    }

    @ExpectedFFDC(value = { "javax.security.auth.login.LoginException", "javax.resource.ResourceException", "java.sql.SQLException" })
    @Test
    public void testProvidedAuthDoesNotExist() throws Exception {
        JsonObject json = new HttpsRequest(server, "/ibm/api/validator/dataSource/DefaultDataSource?auth=container&authAlias=authDoesntExist").run(JsonObject.class);
        String err = "unexpected response: " + json;
        assertEquals(err, "DefaultDataSource", json.getString("uid"));
        assertEquals(err, "DefaultDataSource", json.getString("id"));
        assertFalse(err, json.getBoolean("successful"));
        assertNull(err, json.get("info"));
        assertNull(err, json.get("cause"));

        // Now examine the failure object
        json = json.getJsonObject("failure");
        assertNotNull(err, json);
        assertNull(err, json.get("uid"));
        assertNull(err, json.get("id"));
        assertNull(err, json.get("jndiName"));
        assertNull(err, json.get("failure"));
        assertNull(err, json.get("info"));
        assertNull(err, json.get("sqlState"));
        assertNull(err, json.get("errorCode"));
        assertEquals(err, "java.sql.SQLException", json.getString("class"));
        assertTrue(err, json.getString("message").contains("CWWKS1300E"));
    }

    @Test
    public void testTopLevelConfigDisplayID() throws Exception {
        HttpsRequest request = new HttpsRequest(server, "/ibm/api/validator/dataSource/dataSource[default-0]?auth=application")
                        .requestProp("X-Validator-User", "dbuser")
                        .requestProp("X-Validator-Password", "dbpass");
        JsonObject json = request.method("POST").run(JsonObject.class);
        String err = "unexpected response: " + json;
        assertEquals(err, "dataSource[default-0]", json.getString("uid"));
        assertNull(err, json.get("id"));
        assertEquals(err, "jdbc/defaultauth", json.getString("jndiName"));
        assertTrue(err, json.getBoolean("successful"));
        assertNull(err, json.get("failure"));
        assertNotNull(err, json = json.getJsonObject("info"));
        assertEquals(err, "Apache Derby", json.getString("databaseProductName"));
        assertTrue(err, json.getString("databaseProductVersion").matches(VERSION_REGEX));
        assertEquals(err, "Apache Derby Embedded JDBC Driver", json.getString("jdbcDriverName"));
        assertTrue(err, json.getString("jdbcDriverVersion").matches(VERSION_REGEX));
    }

    @Test
    public void testTopLevelID() throws Exception {
        JsonObject json = new HttpsRequest(server, "/ibm/api/validator/dataSource/DefaultDataSource").run(JsonObject.class);
        String err = "Unexpected json response: " + json;
        assertEquals(err, "DefaultDataSource", json.getString("uid"));
        assertEquals(err, "DefaultDataSource", json.getString("id"));
        assertNull(err, json.get("jndiName"));
        assertTrue(err, json.getBoolean("successful"));
        assertNull(err, json.get("failure"));
        assertNotNull(err, json = json.getJsonObject("info"));
        assertEquals(err, "Apache Derby", json.getString("databaseProductName"));
        assertTrue(err, json.getString("databaseProductVersion").matches(VERSION_REGEX));
        assertEquals(err, "Apache Derby Embedded JDBC Driver", json.getString("jdbcDriverName"));
        assertTrue(err, json.getString("jdbcDriverVersion").matches(VERSION_REGEX));
        assertNull(err, json.get("catalog")); // currently not supported by Derby
        assertEquals(err, "DBUSER", json.getString("schema"));
        assertEquals(err, "dbuser", json.getString("user"));
    }

    @Test
    @ExpectedFFDC(value = { "java.sql.SQLException",
                            "javax.resource.spi.ResourceAllocationException",
                            "com.ibm.ws.rsadapter.exceptions.DataStoreAdapterException" })
    public void testTopLevelIDSQLException() throws Exception {
        JsonObject json = new HttpsRequest(server, "/ibm/api/validator/dataSource/jdbc%2Fnonexistentdb").run(JsonObject.class);
        String err = "unexpected response: " + json;
        assertEquals(err, "jdbc/nonexistentdb", json.getString("uid"));
        assertEquals(err, "jdbc/nonexistentdb", json.getString("id"));
        assertEquals(err, "jdbc/nonexistentdb", json.getString("jndiName"));
        assertFalse(err, json.getBoolean("successful"));
        assertNull(err, json.get("info"));
        assertNull(err, json.get("cause"));
        assertNotNull(err, json = json.getJsonObject("failure"));
        assertNull(err, json.get("uid"));
        assertNull(err, json.get("id"));
        assertNull(err, json.get("jndiName"));
        assertNull(err, json.get("failure"));
        assertNull(err, json.get("info"));
        assertEquals(err, "XJ004", json.getString("sqlState"));
        assertEquals(err, 40000, json.getInt("errorCode"));
        assertEquals(err, "java.sql.SQLException", json.getString("class"));
        assertTrue(err, json.getString("message").contains("memory:doesNotExist"));
        JsonArray stack = json.getJsonArray("stack");
        assertNotNull(err, stack);
        assertTrue(err, stack.size() > 10); // stack is actually much longer, but size could vary
        assertTrue(err, stack.getString(0).startsWith("org.apache.derby."));
        assertTrue(err, stack.getString(1).startsWith("org.apache.derby."));
        assertTrue(err, stack.getString(2).startsWith("org.apache.derby."));
        assertNotNull(err, json = json.getJsonObject("cause"));
        assertNull(err, json.get("uid"));
        assertNull(err, json.get("id"));
        assertNull(err, json.get("jndiName"));
        assertNull(err, json.get("failure"));
        assertNull(err, json.get("info"));
        assertEquals(err, "org.apache.derby.iapi.error.StandardException", json.getString("class"));
        assertTrue(err, json.getString("message").contains("memory:doesNotExist"));
        stack = json.getJsonArray("stack");
        assertNotNull(err, stack);
        assertTrue(err, stack.size() > 10); // stack is actually much longer, but size could vary
        assertTrue(err, stack.getString(0).startsWith("org.apache.derby."));
        assertTrue(err, stack.getString(1).startsWith("org.apache.derby."));
        assertTrue(err, stack.getString(2).startsWith("org.apache.derby."));
    }

    @ExpectedFFDC(value = { "javax.resource.spi.SecurityException", "java.sql.SQLNonTransientException",
                            "javax.resource.spi.ResourceAllocationException", "java.sql.SQLNonTransientConnectionException" })
    @Test
    public void testWrongDefaultAuth() throws Exception {
        JsonObject json = new HttpsRequest(server, "/ibm/api/validator/dataSource/WrongDefaultAuth?auth=container").run(JsonObject.class);
        String err = "unexpected response: " + json;
        assertEquals(err, "WrongDefaultAuth", json.getString("uid"));
        assertEquals(err, "WrongDefaultAuth", json.getString("id"));
        assertFalse(err, json.getBoolean("successful"));
        assertNull(err, json.get("info"));
        assertNull(err, json.get("cause"));

        // Now examine the failure object
        json = json.getJsonObject("failure");
        assertNotNull(err, json);
        assertNull(err, json.get("uid"));
        assertNull(err, json.get("id"));
        assertNull(err, json.get("jndiName"));
        assertNull(err, json.get("successful"));
        assertNull(err, json.get("failure"));
        assertNull(err, json.get("info"));
        assertEquals(err, "08004", json.getString("sqlState"));
        assertEquals(err, 40000, json.getInt("errorCode"));
        assertEquals(err, "java.sql.SQLNonTransientException", json.getString("class"));
        assertTrue(err, json.getString("message").contains("Invalid authentication"));
    }

    @ExpectedFFDC(value = { "javax.resource.spi.SecurityException", "java.sql.SQLNonTransientException",
                            "javax.resource.spi.ResourceAllocationException", "java.sql.SQLNonTransientConnectionException" })
    @Test
    public void testWrongProvidedAuth() throws Exception {
        JsonObject json = new HttpsRequest(server, "/ibm/api/validator/dataSource/DefaultDataSource?auth=container&authAlias=auth2").run(JsonObject.class);
        String err = "unexpected response: " + json;
        assertEquals(err, "DefaultDataSource", json.getString("uid"));
        assertEquals(err, "DefaultDataSource", json.getString("id"));
        assertFalse(err, json.getBoolean("successful"));
        assertNull(err, json.get("info"));
        assertNull(err, json.get("cause"));

        // Now examine the failure object
        json = json.getJsonObject("failure");
        assertNotNull(err, json);
        assertNull(err, json.get("uid"));
        assertNull(err, json.get("id"));
        assertNull(err, json.get("jndiName"));
        assertNull(err, json.get("successful"));
        assertNull(err, json.get("failure"));
        assertNull(err, json.get("info"));
        assertEquals(err, "08004", json.getString("sqlState"));
        assertEquals(err, 40000, json.getInt("errorCode"));
        assertEquals(err, "java.sql.SQLNonTransientException", json.getString("class"));
        assertTrue(err, json.getString("message").contains("Invalid authentication"));
    }
}
