package com.softwareverde.bitcoin.server.module;

import com.softwareverde.bitcoin.server.Configuration;
import com.softwareverde.bitcoin.server.Constants;
import com.softwareverde.bitcoin.server.Environment;
import com.softwareverde.bitcoin.server.database.Database;
import com.softwareverde.bitcoin.util.BitcoinUtil;
import com.softwareverde.database.DatabaseException;
import com.softwareverde.database.mysql.DatabaseInitializer;
import com.softwareverde.database.mysql.MysqlDatabase;
import com.softwareverde.database.mysql.embedded.DatabaseCommandLineArguments;
import com.softwareverde.database.mysql.embedded.EmbeddedMysqlDatabase;
import com.softwareverde.io.Logger;

import java.io.File;

public class DatabaseModule {

    protected final Configuration _configuration;
    protected final Environment _environment;

    protected void _printError(final String errorMessage) {
        System.err.println(errorMessage);
    }

    protected Configuration _loadConfigurationFile(final String configurationFilename) {
        final File configurationFile =  new File(configurationFilename);
        if (! configurationFile.isFile()) {
            _printError("Invalid configuration file.");
            BitcoinUtil.exitFailure();
        }

        return new Configuration(configurationFile);
    }

    public DatabaseModule(final String configurationFilename) {
        _configuration = _loadConfigurationFile(configurationFilename);

        final Configuration.ServerProperties serverProperties = _configuration.getServerProperties();
        final Configuration.DatabaseProperties databaseProperties = _configuration.getDatabaseProperties();

        final MysqlDatabase database = Database.newInstance(_configuration, null);
        if (database == null) {
            Logger.log("Error initializing database.");
            BitcoinUtil.exitFailure();
        }
        Logger.log("[Database Online]");

        _environment = new Environment(database, null);
    }

    public void loop() {
        while (true) {
            try { Thread.sleep(5000); } catch (final Exception e) { }
        }
    }

    public static void execute(final String configurationFileName) {
        final DatabaseModule databaseModule = new DatabaseModule(configurationFileName);
        databaseModule.loop();
    }
}
