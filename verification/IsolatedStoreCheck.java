import java.net.*;
import java.nio.file.*;
import java.util.*;

/** Run against the packaged JAR, with conflicting parent-loader libraries present. */
public final class IsolatedStoreCheck {
    static void check(boolean condition, String description) {
        if (!condition) throw new AssertionError(description);
        System.out.println("PASS " + description);
    }
    static Object store(ClassLoader loader, Path database, Path backups) throws Exception {
        return Class.forName("com.abyssaldominion.siegewar.store.SiegeStore",true,loader)
                .getConstructor(Path.class,int.class,Path.class).newInstance(database,1,backups);
    }
    public static void main(String[] args) throws Exception {
        Path oldJar=Path.of(args[0]), newJar=Path.of(args[1]), slf4j=Path.of(args[2]), h2=Path.of(args[3]), hikari=Path.of(args[4]);
        Path root=Files.createTempDirectory("siege-isolation-"), backups=Files.createDirectories(root.resolve("backups"));
        UUID player=UUID.randomUUID(), session=UUID.randomUUID();
        try (var oldLoader=new URLClassLoader(new URL[]{oldJar.toUri().toURL(),slf4j.toUri().toURL()},ClassLoader.getPlatformClassLoader())) {
            Thread.currentThread().setContextClassLoader(oldLoader);
            Object old=store(oldLoader,root.resolve("legacy"),backups);
            old.getClass().getMethod("importPlayer",UUID.class,String.class,long.class,long.class,long.class,long.class)
                    .invoke(old,player,"Legacy",77L,2L,1L,1L);
            old.getClass().getMethod("importOwnership",UUID.class,String.class).invoke(old,player,"wizard");
            Class<?> escrow=Class.forName("com.abyssaldominion.siegewar.store.SiegeStore$Escrow",true,oldLoader);
            Class phase=Class.forName("com.abyssaldominion.siegewar.store.SiegeStore$Phase",true,oldLoader);
            Object captured=escrow.getConstructor(UUID.class,UUID.class,UUID.class,String.class,phase,int.class,byte[].class)
                    .newInstance(player,session,UUID.randomUUID(),"castle",Enum.valueOf(phase,"CAPTURED"),1,new byte[]{1,2,3});
            old.getClass().getMethod("capture",escrow).invoke(old,captured);
            ((AutoCloseable)old).close();
        }
        try (var parent=new URLClassLoader(new URL[]{h2.toUri().toURL(),hikari.toUri().toURL(),slf4j.toUri().toURL()},ClassLoader.getPlatformClassLoader());
             var child=new URLClassLoader(new URL[]{newJar.toUri().toURL()},parent)) {
            Class.forName("org.h2.Driver",true,parent);
            Class.forName("com.zaxxer.hikari.HikariConfig",true,parent);
            Thread.currentThread().setContextClassLoader(parent);
            Object current=store(child,root.resolve("legacy"),backups);
            Class<?> type=current.getClass();
            check(Thread.currentThread().getContextClassLoader()==parent,"Original thread context loader restored");
            String driver=(String)type.getMethod("driverClass").invoke(null);
            check(driver.equals("com.abyssaldominion.siegewar.internal.h2.Driver"),"Packaged H2 driver uses private namespace");
            check(Class.forName(driver,true,child).getClassLoader()==child,"Private H2 driver belongs to plugin loader");
            check(Class.forName("com.abyssaldominion.siegewar.internal.hikari.HikariConfig",true,child).getClassLoader()==child,"Private HikariCP ignores conflicting parent version");
            check((long)type.getMethod("balance",UUID.class).invoke(current,player)==77L,"Unshaded 1.0.2 database retains balance in 1.0.3");
            check(((Set<?>)type.getMethod("owned",UUID.class).invoke(current,player)).contains("wizard"),"Unshaded 1.0.2 kit ownership retained");
            Object pending=((Optional<?>)type.getMethod("escrow",UUID.class).invoke(current,player)).orElseThrow();
            check(pending.getClass().getMethod("session").invoke(pending).equals(session),"Pending 1.0.2 inventory escrow session retained");
            check(Arrays.equals(new byte[]{1,2,3},(byte[])pending.getClass().getMethod("snapshot").invoke(pending)),"Pending 1.0.2 snapshot bytes retained exactly");
            Path script=backups.resolve("packaged.sql");
            type.getMethod("backup",Path.class).invoke(current,script);
            ((AutoCloseable)current).close();
            Object restored=store(child,root.resolve("restored"),backups);
            type.getMethod("restore",Path.class).invoke(restored,script);
            check((long)type.getMethod("balance",UUID.class).invoke(restored,player)==77L,"Shaded SCRIPT/RUNSCRIPT round trip retains balance");
            ((AutoCloseable)restored).close();
        }
        System.out.println("PACKAGED_ISOLATION_COMPLETE");
    }
}
