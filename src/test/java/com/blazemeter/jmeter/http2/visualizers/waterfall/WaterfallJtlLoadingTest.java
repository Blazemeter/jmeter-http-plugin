package com.blazemeter.jmeter.http2.visualizers.waterfall;

import static org.assertj.core.api.Assertions.assertThat;

import com.blazemeter.jmeter.http2.sampler.JMeterTestUtils;
import com.blazemeter.jmeter.http2.visualizers.waterfall.model.SampleRecord;
import com.blazemeter.jmeter.http2.visualizers.waterfall.model.WaterfallStore;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import org.apache.jmeter.reporters.ResultCollector;
import org.apache.jmeter.samplers.SampleResult;
import org.apache.jmeter.save.CSVSaveService;
import org.apache.jmeter.visualizers.Visualizer;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Checks that a {@code .jtl} written by a non-GUI run carries the fields the waterfall draws with.
 *
 * <p>This is the test that matters for the file-loading feature, because the viewer never sees the
 * file: JMeter's own {@link CSVSaveService} parses it and hands rebuilt {@code SampleResult}s to
 * whatever {@link Visualizer} is attached, which is the same entry point a live run uses. So the
 * question worth asserting is not whether the viewer can read a file, but which of the columns it
 * needs survive the round trip. Connect time, latency, idle time and bytes do. The URL and the
 * protocol do not, for reasons in JMeter rather than here, and that is asserted as well so the
 * limitation stays recorded rather than being rediscovered.
 */
public class WaterfallJtlLoadingTest {

  private static final String HEADER = "timeStamp,elapsed,label,responseCode,responseMessage,"
      + "threadName,dataType,success,failureMessage,bytes,sentBytes,grpThreads,allThreads,URL,"
      + "Latency,IdleTime,Connect";

  @Rule
  public TemporaryFolder folder = new TemporaryFolder();

  private WaterfallStore store;

  @Before
  public void setUp() {
    JMeterTestUtils.setupJmeterEnv();
    store = new WaterfallStore(-1);
  }

  /**
   * Feeds a JTL through JMeter's own reader into the store.
   *
   * @param rows the data rows, header excluded
   * @return the records the viewer would show
   * @throws Exception when the temporary file cannot be written
   */
  private List<SampleRecord> load(String... rows) throws Exception {
    File file = folder.newFile("results.jtl");
    StringBuilder content = new StringBuilder(HEADER).append('\n');
    for (String row : rows) {
      content.append(row).append('\n');
    }
    Files.write(file.toPath(), content.toString().getBytes(StandardCharsets.UTF_8));
    Visualizer visualizer = new StoringVisualizer(store);
    CSVSaveService.processSamples(file.getAbsolutePath(), visualizer, new ResultCollector());
    store.drainPending(1000);
    return store.getRecords();
  }

  @Test
  public void timingsAndPhasesSurviveTheRoundTrip() throws Exception {
    List<SampleRecord> records = load(
        "1700000000000,520,Home Page,200,OK,Shoppers 1-1,text,true,,15234,412,5,5,"
            + "https://example.com/home,310,0,90");

    assertThat(records).hasSize(1);
    SampleRecord record = records.get(0);
    assertThat(record.getLabel()).isEqualTo("Home Page");
    assertThat(record.getResponseCode()).isEqualTo("200");
    assertThat(record.getMethod()).isEmpty();
    assertThat(record.getThreadGroup()).isEqualTo("Shoppers");
    assertThat(record.getBytes()).isEqualTo(15234);
    assertThat(record.getSentBytes()).isEqualTo(412);
    assertThat(record.getElapsed()).isEqualTo(520);
    assertThat(record.getPhases().getConnectMs()).isEqualTo(90);
    assertThat(record.getPhases().getTtfbMs()).isEqualTo(220);
    assertThat(record.getPhases().getDownloadMs()).isEqualTo(210);
    assertThat(record.getPhases().isDetailed()).isTrue();
  }

  @Test
  public void aFailedRowKeepsItsStatusAndIsMarkedAsFailed() throws Exception {
    List<SampleRecord> records = load(
        "1700000000000,3001,Checkout,500,Internal Server Error,Shoppers 1-2,text,false,"
            + "Assertion failed,120,80,5,5,https://example.com/checkout,2900,0,0");

    SampleRecord record = records.get(0);
    assertThat(record.isSuccess()).isFalse();
    assertThat(record.getStatusClass()).isEqualTo(5);
    assertThat(record.getElapsed()).isEqualTo(3001);
  }

  @Test
  public void idleTimeBecomesTheGreyLeadingSegment() throws Exception {
    List<SampleRecord> records = load(
        "1700000000000,400,Transaction,200,OK,Shoppers 1-1,text,true,,10,10,5,5,,0,150,0");

    SampleRecord record = records.get(0);
    assertThat(record.getPhases().getIdleMs()).isEqualTo(150);
    assertThat(record.getPhases().getSpanMs()).isEqualTo(550);
    assertThat(record.getEndTime() - record.getStartTime()).isEqualTo(550);
  }

  /**
   * Whether a JTL's {@code timeStamp} column is the start or the end of a sample is decided by the
   * {@code sampleresult.timestamp.start} property of the JMeter reading the file, not of the one
   * that wrote it. The bars are positioned from whatever {@code SampleResult} resolved it to, so
   * the expectation here is derived the same way rather than hard-coded - and a mismatched property
   * shifts every bar right by its own duration, which is worth knowing about.
   */
  @Test
  public void severalRowsKeepTheirOrderAndSpanTheWholeTimeline() throws Exception {
    List<SampleRecord> records = load(
        "1700000000000,100,first,200,OK,TG 1-1,text,true,,10,10,1,1,,50,0,10",
        "1700000000500,200,second,200,OK,TG 1-1,text,true,,10,10,1,1,,80,0,0",
        "1700000001000,300,third,200,OK,TG 1-1,text,true,,10,10,1,1,,90,0,0");

    assertThat(records).extracting(SampleRecord::getLabel)
        .containsExactly("first", "second", "third");
    boolean stampsAreStarts = new SampleResult().isStampedAtStart();
    long firstStart = stampsAreStarts ? 1700000000000L : 1700000000000L - 100;
    long thirdEnd = stampsAreStarts ? 1700000001300L : 1700000001000L;
    assertThat(records.get(0).getStartTime()).isEqualTo(firstStart);
    assertThat(records.get(2).getEndTime()).isEqualTo(thirdEnd);
    assertThat(records.get(0).getStartTime()).isLessThan(records.get(2).getStartTime());
  }

  /**
   * Two columns a waterfall would like are not recoverable from a CSV JTL, and both are JMeter's
   * doing rather than the viewer's: the reader skips the URL column outright, and the protocol
   * lives in the response status line, which only the XML format stores. Asserted so the limitation
   * stays documented instead of being rediscovered.
   */
  @Test
  public void aCsvJtlCarriesNeitherTheUrlNorTheStatusLine() throws Exception {
    List<SampleRecord> records = load(
        "1700000000000,100,Home Page,200,OK,TG 1-1,text,true,,10,10,1,1,"
            + "https://example.com/home,50,0,10");

    assertThat(records.get(0).getUrl()).isEmpty();
    assertThat(records.get(0).getProtocol()).isEmpty();
  }

  /** The minimum a JMeter result reader needs on the other end. */
  private static final class StoringVisualizer implements Visualizer {

    private final WaterfallStore store;

    private StoringVisualizer(WaterfallStore store) {
      this.store = store;
    }

    @Override
    public void add(SampleResult sample) {
      store.offer(sample);
    }

    @Override
    public boolean isStats() {
      return false;
    }
  }
}
