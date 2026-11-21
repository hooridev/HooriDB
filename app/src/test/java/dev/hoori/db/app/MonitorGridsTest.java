package dev.hoori.db.app;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class MonitorGridsTest {

  @Test
  void aTimeIsCompact() {
    assertEquals("", MonitorGrids.elapsed(null));
    assertEquals("850 ms", MonitorGrids.elapsed(Duration.ofMillis(850)));
    assertEquals("12 s", MonitorGrids.elapsed(Duration.ofMillis(12_900)));
    assertEquals("4:03", MonitorGrids.elapsed(Duration.ofSeconds(4 * 60 + 3)));
    assertEquals("1:07:12", MonitorGrids.elapsed(Duration.ofSeconds(3600 + 7 * 60 + 12)));
    assertEquals("2 d 5 h", MonitorGrids.elapsed(Duration.ofHours(53)));
  }

  @Test
  void aWholeNumberIsGroupedAndAnythingElseStaysAsItIs() {
    assertEquals("1,234,567", MonitorGrids.grouped("1234567"));
    assertEquals("-12", MonitorGrids.grouped("-12"));
    assertEquals("12.5", MonitorGrids.grouped("12.5"));
    assertEquals("ON", MonitorGrids.grouped("ON"));
    assertEquals("", MonitorGrids.grouped(""));
  }
}
