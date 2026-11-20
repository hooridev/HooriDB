package dev.hoori.db.core.document.mongo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mongodb.ServerAddress;
import com.mongodb.connection.ClusterConnectionMode;
import dev.hoori.db.core.connect.DocumentConnector;
import dev.hoori.db.core.connection.ConnectionProfile;
import dev.hoori.db.core.connection.Provider;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The address the driver is given for a profile, without looking anything up. */
class MongoTest {

  private static final Duration TIMEOUT = Duration.ofSeconds(5);

  @Test
  void anSrvProfileNamesTheRecordAndTakesEveryServerItLists() {
    var profile = ConnectionProfile.of(Provider.MONGODB)
        .withHost("Cluster0.Example.NET")
        .withPort(1)
        .withSrv(true);
    var settings = Mongo.settings(DocumentConnector.target(profile, TIMEOUT)).build();
    var cluster = settings.getClusterSettings();
    assertEquals("cluster0.example.net", cluster.getSrvHost());
    assertEquals(ClusterConnectionMode.MULTIPLE, cluster.getMode());
    assertTrue(settings.getSslSettings().isEnabled());
    var rooted = Mongo.settings(DocumentConnector.target(profile.withHost("example.net."), TIMEOUT))
        .build()
        .getClusterSettings();
    assertEquals("example.net", rooted.getSrvHost());
  }

  @Test
  void aHostProfileConnectsToThatServerAlone() {
    var profile = ConnectionProfile.of(Provider.MONGODB).withHost("db.example").withPort(27018);
    var cluster =
        Mongo.settings(DocumentConnector.target(profile, TIMEOUT)).build().getClusterSettings();
    assertNull(cluster.getSrvHost());
    assertEquals(List.of(new ServerAddress("db.example", 27018)), cluster.getHosts());
    assertEquals(ClusterConnectionMode.SINGLE, cluster.getMode());
  }
}
