package com.abyssaldominion.siegewar;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/** Build metadata shares its source with Maven's dependency and manifest versions. */
public final class BuildInfo {
    private static final Properties VALUES = read();
    private static Properties read() {
        try (InputStream in = BuildInfo.class.getResourceAsStream("/build.properties")) {
            if (in == null) throw new IOException("Missing build.properties");
            Properties values = new Properties();
            values.load(in);
            for (String key : new String[]{"version", "h2", "hikaricp"})
                if (values.getProperty(key, "").isBlank() || values.getProperty(key).contains("${"))
                    throw new IOException("Unfiltered build metadata: " + key);
            return values;
        } catch (IOException ex) { throw new ExceptionInInitializerError(ex); }
    }
    public static String version() { return VALUES.getProperty("version"); }
    public static String h2() { return VALUES.getProperty("h2"); }
    public static String hikari() { return VALUES.getProperty("hikaricp"); }
    private BuildInfo() {}
}
