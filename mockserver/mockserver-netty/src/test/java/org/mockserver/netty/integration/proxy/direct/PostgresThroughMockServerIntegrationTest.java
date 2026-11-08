package org.mockserver.netty.integration.proxy.direct;

import io.netty.buffer.ByteBufUtil;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockserver.configuration.Configuration;
import org.mockserver.integration.ClientAndServer;
import org.mockserver.matchers.TimeToLive;
import org.mockserver.matchers.Times;
import org.mockserver.mock.Expectation;
import org.mockserver.socket.PortFactory;
import org.mockserver.test.DockerAvailability;
import org.mockserver.test.TestContainerImages;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.utility.DockerImageName;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThrows;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.integration.ClientAndServer.startClientAndServer;
import static org.mockserver.model.BinaryRequestDefinition.binaryRequest;
import static org.mockserver.model.BinaryResponse.binaryResponse;
import static org.mockserver.stop.Stop.stopQuietly;
import static org.mockserver.test.Retries.tryWaitForSuccess;

/**
 * A real PostgreSQL session through MockServer forwarding a port, driven by the PostgreSQL JDBC driver. With
 * {@code sslmode=require} the driver opens in the clear with an {@code SSLRequest} and turns TLS on part way
 * through the connection; the server has {@code ssl=on} and SCRAM authentication, so it also offers channel
 * binding. MockServer keeps one upstream connection for the client connection and upgrades it to TLS when the
 * client does. Docker-gated.
 * <p>
 * The same session also runs with MockServer as the driver's SOCKS5 or HTTP CONNECT proxy, the tunnel relayed as a
 * binary connection to the server.
 * <p>
 * With {@code matchBinaryExpectationsWhenProxying} and the driver in simple query mode, binary expectations
 * answer chosen queries of the same session while the rest reach the server.
 */
public class PostgresThroughMockServerIntegrationTest {

    private static final int POSTGRES_PORT = 5432;
    private static final byte[] READY_FOR_QUERY_IDLE = {'Z', 0, 0, 0, 5, 'I'};
    private static final Pattern HEX = Pattern.compile("[0-9a-f]{10,}");
    private static final String PASSWORD = "mockserver-test-password";
    // the server's certificate and key; the channel binding case gives MockServer the same pair to present,
    // with the authority that signed it, which MockServer requires of a fixed certificate
    private static final String SERVER_CERTIFICATE = "org/mockserver/netty/integration/tls/leaf-cert.pem";
    private static final String SERVER_PRIVATE_KEY = "org/mockserver/netty/integration/tls/leaf-key-pkcs8.pem";
    private static final String SERVER_CERTIFICATE_AUTHORITY = "org/mockserver/netty/integration/tls/ca.pem";
    private static final String STARTUP_COMMAND = "cp /tmp/server.crt /tmp/server.key /var/lib/postgresql/"
        + " && chown postgres:postgres /var/lib/postgresql/server.crt /var/lib/postgresql/server.key"
        + " && chmod 600 /var/lib/postgresql/server.key"
        + " && exec docker-entrypoint.sh postgres -c ssl=on"
        + " -c ssl_cert_file=/var/lib/postgresql/server.crt -c ssl_key_file=/var/lib/postgresql/server.key";

    private static GenericContainer<?> postgres;
    private ClientAndServer mockServer;

    @BeforeClass
    public static void startPostgres() throws IOException {
        Assume.assumeTrue("Docker is not available", DockerAvailability.isAvailable(() -> DockerClientFactory.instance().isDockerAvailable()));
        postgres = new GenericContainer<>(DockerImageName.parse(TestContainerImages.POSTGRES))
            .withEnv("POSTGRES_PASSWORD", PASSWORD)
            .withExposedPorts(POSTGRES_PORT)
            .withCopyToContainer(Transferable.of(classpathBytes(SERVER_CERTIFICATE)), "/tmp/server.crt")
            .withCopyToContainer(Transferable.of(classpathBytes(SERVER_PRIVATE_KEY)), "/tmp/server.key")
            .withCommand("sh", "-c", STARTUP_COMMAND)
            // the image's set-up runs a server of its own first, so the second notice is the real one
            .waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*", 2).withStartupTimeout(Duration.ofSeconds(120)));
        postgres.start();
    }

    @AfterClass
    public static void stopPostgres() {
        if (postgres != null) {
            postgres.stop();
        }
    }

    @After
    public void stopMockServer() {
        stopQuietly(mockServer);
    }

    private static byte[] classpathBytes(String path) throws IOException {
        try (InputStream input = PostgresThroughMockServerIntegrationTest.class.getClassLoader().getResourceAsStream(path)) {
            if (input == null) {
                throw new IOException("not on the classpath: " + path);
            }
            return input.readAllBytes();
        }
    }

    private void startMockServer(Configuration configuration) {
        mockServer = startClientAndServer(configuration, postgres.getHost(), postgres.getMappedPort(POSTGRES_PORT), PortFactory.findFreePort());
    }

    private static Connection connect(String host, int port, String applicationName, String sslMode, String channelBinding) throws SQLException {
        return connect(host, port, applicationName, sslMode, channelBinding, new Properties());
    }

    private static Connection connect(String host, int port, String applicationName, String sslMode, String channelBinding, Properties extra) throws SQLException {
        Properties properties = new Properties();
        properties.setProperty("user", "postgres");
        properties.setProperty("password", PASSWORD);
        properties.setProperty("sslmode", sslMode);
        properties.setProperty("channelBinding", channelBinding);
        properties.setProperty("ApplicationName", applicationName);
        properties.setProperty("connectTimeout", "10");
        properties.setProperty("loginTimeout", "30");
        properties.setProperty("socketTimeout", "60");
        properties.putAll(extra);
        return DriverManager.getConnection("jdbc:postgresql://" + host + ":" + port + "/postgres", properties);
    }

    private Connection connectThroughMockServer(String applicationName, String sslMode, String channelBinding) throws SQLException {
        return connect("127.0.0.1", mockServer.getLocalPort(), applicationName, sslMode, channelBinding);
    }

    /** Each statement one {@code Query} message, the same bytes every time, as binary expectations need. */
    private Connection connectInSimpleQueryMode(String applicationName, String sslMode, int socketTimeoutSeconds) throws SQLException {
        Properties extra = new Properties();
        extra.setProperty("preferQueryMode", "simple");
        extra.setProperty("socketTimeout", String.valueOf(socketTimeoutSeconds));
        return connect("127.0.0.1", mockServer.getLocalPort(), applicationName, sslMode, "disable", extra);
    }

    private static Connection connectDirectly(String applicationName, String sslMode, String channelBinding) throws SQLException {
        return connect(postgres.getHost(), postgres.getMappedPort(POSTGRES_PORT), applicationName, sslMode, channelBinding);
    }

    private static Object queryOne(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet resultSet = statement.executeQuery(sql)) {
            assertThat("a row from: " + sql, resultSet.next(), is(true));
            return resultSet.getObject(1);
        }
    }

    /** A session's worth of work, then which backend served it and whether its connection to that backend is TLS. */
    private static void assertWholeSession(Connection connection, String applicationName, boolean overTls) throws SQLException {
        Object backend = queryOne(connection, "select pg_backend_pid()");

        assertThat(queryOne(connection, "select 1"), is(1));
        // past the driver's prepareThreshold of 5 the statement is named on the server, which only its own backend knows
        try (PreparedStatement statement = connection.prepareStatement("select ?::int + 1")) {
            for (int i = 0; i < 8; i++) {
                statement.setInt(1, 41 + i);
                try (ResultSet resultSet = statement.executeQuery()) {
                    assertThat(resultSet.next(), is(true));
                    assertThat(resultSet.getInt(1), is(42 + i));
                }
            }
        }
        // one row larger than 64 KiB, then many small ones
        String large = (String) queryOne(connection, "select repeat('x', 200000)");
        assertThat(large.length(), is(200000));
        assertThat(large.replace("x", "").isEmpty(), is(true));
        assertThat(queryOne(connection, "select sum(i) from generate_series(1, 20000) i").toString(), is("200010000"));
        assertThat("the upstream connection is TLS", queryOne(connection, "select ssl from pg_stat_ssl where pid = pg_backend_pid()"), is(overTls));

        assertThat("the same backend throughout", queryOne(connection, "select pg_backend_pid()"), is(backend));
        try (Connection admin = connectDirectly("admin-" + UUID.randomUUID(), "disable", "disable");
             PreparedStatement statement = admin.prepareStatement("select count(*), min(pid) from pg_stat_activity where application_name = ?")) {
            statement.setString(1, applicationName);
            try (ResultSet resultSet = statement.executeQuery()) {
                assertThat(resultSet.next(), is(true));
                assertThat("one backend for the one connection", resultSet.getLong(1), is(1L));
                assertThat(resultSet.getObject(2), is(backend));
            }
        }
    }

    @Test
    public void shouldReachPostgresDirectlyOverTlsWithChannelBinding() throws SQLException {
        // the harness itself: the server offers TLS and channel binding, and the certificate supports binding
        String applicationName = "direct-" + UUID.randomUUID();
        try (Connection connection = connectDirectly(applicationName, "require", "require")) {
            assertWholeSession(connection, applicationName, true);
        }
    }

    @Test
    public void shouldRunASessionInTheClearThroughMockServer() throws SQLException {
        startMockServer(configuration());
        String applicationName = "clear-" + UUID.randomUUID();
        try (Connection connection = connectThroughMockServer(applicationName, "disable", "disable")) {
            assertWholeSession(connection, applicationName, false);
        }
    }

    @Test
    public void shouldRunASessionThatUpgradesToTlsThroughMockServer() throws SQLException {
        startMockServer(configuration());
        String applicationName = "tls-" + UUID.randomUUID();
        // MockServer presents a certificate of its own, so the server's channel binding cannot be used
        try (Connection connection = connectThroughMockServer(applicationName, "require", "disable")) {
            assertWholeSession(connection, applicationName, true);
        }
    }

    /** The driver reaches the server through MockServer as its SOCKS5 or HTTP CONNECT proxy. */
    private Connection connectThroughMockServerAsProxy(String tunnel, String applicationName, String sslMode) throws SQLException {
        Properties extra = new Properties();
        extra.setProperty("socketFactory", TunnelSocketFactory.class.getName());
        extra.setProperty("socketFactoryArg", tunnel + ":127.0.0.1:" + mockServer.getLocalPort());
        return connect(postgres.getHost(), postgres.getMappedPort(POSTGRES_PORT), applicationName, sslMode, "disable", extra);
    }

    @Test
    public void shouldRunASessionThroughMockServerAsASocksOrConnectProxy() throws SQLException {
        mockServer = startClientAndServer(configuration());
        for (String tunnel : new String[]{"SOCKS", "CONNECT"}) {
            for (String sslMode : new String[]{"disable", "require"}) {
                String applicationName = tunnel.toLowerCase() + "-" + sslMode + "-" + UUID.randomUUID();
                long receivedBefore = receivedBinaryRequests();
                try (Connection connection = connectThroughMockServerAsProxy(tunnel, applicationName, sslMode)) {
                    assertWholeSession(connection, applicationName, sslMode.equals("require"));
                }
                tryWaitForSuccess(() -> assertThat(tunnel + " " + sslMode + ": the session crossed MockServer as a binary connection", receivedBinaryRequests() > receivedBefore, is(true)));
            }
        }
    }

    private long receivedBinaryRequests() {
        return Arrays.stream(mockServer.retrieveLogMessagesArray(null)).filter(logMessage -> logMessage.contains("received binary request")).count();
    }

    @Test
    public void shouldKeepChannelBindingWhenMockServerPresentsTheServersOwnCertificate() throws SQLException {
        // the binding PostgreSQL uses is a hash of the server's certificate, which here is the one MockServer presents
        startMockServer(configuration()
            .privateKeyPath(SERVER_PRIVATE_KEY)
            .x509CertificatePath(SERVER_CERTIFICATE)
            .certificateAuthorityCertificate(SERVER_CERTIFICATE_AUTHORITY));
        String applicationName = "bound-" + UUID.randomUUID();
        try (Connection connection = connectThroughMockServer(applicationName, "require", "require")) {
            assertWholeSession(connection, applicationName, true);
        }
    }

    @Test
    public void shouldFailChannelBindingWhenMockServerPresentsACertificateOfItsOwn() {
        startMockServer(configuration());

        SQLException refused = assertThrows(SQLException.class, () -> connectThroughMockServer("unbound-" + UUID.randomUUID(), "require", "require").close());

        assertThat(refused.getMessage(), containsString("channel binding"));
    }

    @Test
    public void shouldAnswerASelectFromAnExpectationCapturedFromTheLogWhileOtherQueriesReachTheServer() throws SQLException {
        startMockServer(matchingExpectations());
        String canned = "select name from customer where id = 7";
        try (Connection connection = connectInSimpleQueryMode("canned-" + UUID.randomUUID(), "disable", 60)) {
            Object backend = queryOne(connection, "select pg_backend_pid()");

            // a first pass reaches the server, which has no such table, and leaves the exact bytes in MockServer's log
            SQLException noSuchTable = assertThrows(SQLException.class, () -> queryOne(connection, canned));
            assertThat(noSuchTable.getSQLState(), is("42P01"));
            byte[] captured = loggedBinaryRequestContaining(canned);
            assertThat("the log holds the Query message whole", ByteBufUtil.hexDump(captured), is(ByteBufUtil.hexDump(query(canned))));

            mockServer.upsert(new Expectation(binaryRequest(captured)).thenRespondWithBinary(binaryResponse(concat(
                rowDescription("name"),
                dataRow("Alice"),
                message('C', cString("SELECT 1")),
                READY_FOR_QUERY_IDLE
            ))));

            assertThat(queryOne(connection, canned), is("Alice"));
            assertThat("other queries are the server's", queryOne(connection, "select 1"), is(1));
            assertThat(queryOne(connection, canned), is("Alice"));
            assertThat("the same backend throughout", queryOne(connection, "select pg_backend_pid()"), is(backend));
        }
    }

    @Test
    public void shouldFailAnInsertOnceFromAnExpectationOverTlsAndLetTheRetryReachTheServer() throws SQLException {
        String table = "items_" + UUID.randomUUID().toString().replace("-", "");
        executeDirectly("create table " + table + " (id int)");
        startMockServer(matchingExpectations());
        String insert = "insert into " + table + " values (42)";
        mockServer.upsert(new Expectation(binaryRequest(query(insert)), Times.once(), TimeToLive.unlimited(), 0).thenRespondWithBinary(binaryResponse(concat(
            errorResponse("40001", "could not serialize access due to concurrent update"),
            READY_FOR_QUERY_IDLE
        ))));

        try (Connection connection = connectInSimpleQueryMode("insert-" + UUID.randomUUID(), "require", 60);
             Statement statement = connection.createStatement()) {
            assertThat("the session is TLS to the server", queryOne(connection, "select ssl from pg_stat_ssl where pid = pg_backend_pid()"), is(true));

            SQLException injected = assertThrows(SQLException.class, () -> statement.executeUpdate(insert));
            assertThat(injected.getSQLState(), is("40001"));
            assertThat(injected.getMessage(), containsString("could not serialize access"));
            assertThat("used up", mockServer.retrieveActiveExpectations(null).length, is(0));

            assertThat("the retry reaches the server", statement.executeUpdate(insert), is(1));
        }
        assertThat("exactly one row, counted on a connection of its own", countDirectly(table), is(1L));
    }

    @Test
    public void shouldTimeOutAQueryAnExpectationDropsWithoutItReachingTheServer() throws Exception {
        String table = "dropped_" + UUID.randomUUID().toString().replace("-", "");
        executeDirectly("create table " + table + " (id int)");
        startMockServer(matchingExpectations());
        String insert = "insert into " + table + " values (1)";
        mockServer.upsert(new Expectation(binaryRequest(query(insert))).thenRespondWithBinary(binaryResponse(new byte[0])));

        try (Connection connection = connectInSimpleQueryMode("dropped-" + UUID.randomUUID(), "disable", 3);
             Statement statement = connection.createStatement()) {
            assertThat(queryOne(connection, "select 1"), is(1));

            SQLException timedOut = assertThrows(SQLException.class, () -> statement.executeUpdate(insert));
            assertThat(timedOut.getCause(), instanceOf(SocketTimeoutException.class));
        }
        assertThat(Arrays.stream(mockServer.retrieveLogMessagesArray(null)).filter(logMessage -> logMessage.contains("returning nothing")).count(), is(1L));
        assertThat("no statement reached the server", countDirectly(table), is(0L));
    }

    private static Configuration matchingExpectations() {
        return configuration().logLevel("INFO").matchBinaryExpectationsWhenProxying(true);
    }

    private static void executeDirectly(String sql) throws SQLException {
        try (Connection admin = connectDirectly("admin-" + UUID.randomUUID(), "disable", "disable"); Statement statement = admin.createStatement()) {
            statement.execute(sql);
        }
    }

    private static long countDirectly(String table) throws SQLException {
        try (Connection admin = connectDirectly("admin-" + UUID.randomUUID(), "disable", "disable")) {
            return ((Number) queryOne(admin, "select count(*) from " + table)).longValue();
        }
    }

    /**
     * The bytes of the one binary request MockServer logged whose text holds {@code sql}, read back from its log,
     * which is written off the event loop.
     */
    private byte[] loggedBinaryRequestContaining(String sql) {
        String sqlHex = ByteBufUtil.hexDump(sql.getBytes(StandardCharsets.UTF_8));
        List<String> found = new ArrayList<>();
        tryWaitForSuccess(() -> {
            found.clear();
            for (String logMessage : mockServer.retrieveLogMessagesArray(null)) {
                if (logMessage.contains("received binary request")) {
                    Matcher hex = HEX.matcher(logMessage);
                    while (hex.find()) {
                        if (hex.group().contains(sqlHex)) {
                            found.add(hex.group());
                        }
                    }
                }
            }
            assertThat(found, hasSize(1));
        });
        return ByteBufUtil.decodeHexDump(found.get(0));
    }

    /** A message of the PostgreSQL protocol: its type, then its length, which counts itself but not the type. */
    private static byte[] message(char type, byte[]... parts) {
        byte[] body = concat(parts);
        ByteArrayOutputStream message = new ByteArrayOutputStream();
        message.write(type);
        message.writeBytes(int32(body.length + 4));
        message.writeBytes(body);
        return message.toByteArray();
    }

    private static byte[] query(String sql) {
        return message('Q', cString(sql));
    }

    /** One text column; type 25 is {@code text}. */
    private static byte[] rowDescription(String column) {
        return message('T', int16(1), cString(column), int32(0), int16(0), int32(25), int16(-1), int32(-1), int16(0));
    }

    private static byte[] dataRow(String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        return message('D', int16(1), int32(bytes.length), bytes);
    }

    private static byte[] errorResponse(String sqlState, String text) {
        return message('E', new byte[]{'S'}, cString("ERROR"), new byte[]{'V'}, cString("ERROR"), new byte[]{'C'}, cString(sqlState), new byte[]{'M'}, cString(text), new byte[]{0});
    }

    private static byte[] cString(String text) {
        return concat(text.getBytes(StandardCharsets.UTF_8), new byte[]{0});
    }

    private static byte[] int16(int value) {
        return new byte[]{(byte) (value >> 8), (byte) value};
    }

    private static byte[] int32(int value) {
        return new byte[]{(byte) (value >> 24), (byte) (value >> 16), (byte) (value >> 8), (byte) value};
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream all = new ByteArrayOutputStream();
        for (byte[] part : parts) {
            all.writeBytes(part);
        }
        return all.toByteArray();
    }
}
