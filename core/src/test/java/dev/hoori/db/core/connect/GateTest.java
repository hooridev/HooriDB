package dev.hoori.db.core.connect;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import org.junit.jupiter.api.Test;

class GateTest {

  @Test
  void aGateClosedUnopenedReleasesItsClientsAndOpensNothing() throws Exception {
    try (var upstream = new ServerSocket(0, 50, InetAddress.getByName(Gate.HOST));
        var client = new Socket()) {
      var gate = new Gate(Gate.HOST, upstream.getLocalPort());
      client.connect(new InetSocketAddress(Gate.HOST, gate.port()));
      assertTrue(gate.awaitClient());
      gate.close();
      client.setSoTimeout(2000);
      assertEquals(-1, client.getInputStream().read());
      upstream.setSoTimeout(500);
      assertThrows(SocketTimeoutException.class, upstream::accept);
    }
  }
}
