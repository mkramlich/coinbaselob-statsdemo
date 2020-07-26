package mkramlichgithub.coinbaselob;

import java.util.ArrayList;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import junit.framework.TestCase;

public class StatsDemoTest extends TestCase {

    static Logger log = LoggerFactory.getLogger(StatsDemoTest.class);

    public void testSnapshotForUnsupportedProductId() {
        ArrayList<String> lines = new ArrayList<>();
        lines.add( "{\"type\":\"snapshot\",\"product_id\":\"USD-ETH\",\"asks\":[[\"2.0\",\"1.0\"]],\"bids\":[[\"1.0\",\"2.0\"]]}");

        StatsDemo app = new StatsDemo();
        app.lines = lines;
        app.process();

        helpAssertStats(app, 0, 0, null, null, null, null);
    }

    public void testSnapshotOneEach() {
        ArrayList<String> lines = new ArrayList<>();
        lines.add( "{\"type\":\"snapshot\",\"product_id\":\"BTC-USD\",\"asks\":[[\"2.0\",\"1.0\"]],\"bids\":[[\"1.0\",\"2.0\"]]}");

        StatsDemo app = new StatsDemo();
        app.lines = lines;
        app.process();

        helpAssertStats(app, 1, 1, 2.0, 2.0, 1.0, 1.0);

        Object[] entriesArray = app.asksMap.entrySet().toArray();
        helpAssertEntry(entriesArray, "ask", 0, 2.0, 1.0, 0.0, 0.0);

        entriesArray = app.bidsMap.descendingMap().entrySet().toArray();
        helpAssertEntry(entriesArray, "bid", 0, 1.0, 2.0, 0.0, 0.0);
    }

    public void testSnapshotWithNoAsksOrBidsFields() {
        ArrayList<String> lines = new ArrayList<>();
        lines.add("{\"type\":\"snapshot\",\"product_id\":\"BTC-USD\"}");

        StatsDemo app = new StatsDemo();
        app.lines = lines;
        app.process();

        helpAssertStats(app, 0, 0, null, null, null, null);
    }

    public void testSnapshotWhereAsksOrBidsAreEmptyArrays() {
        ArrayList<String> lines = new ArrayList<>();
        lines.add("{\"type\":\"snapshot\",\"product_id\":\"BTC-USD\",\"asks\":[],\"bids\":[]}");

        StatsDemo app = new StatsDemo();
        app.lines = lines;
        app.process();

        helpAssertStats(app, 0, 0, null, null, null, null);
    }

    public void testL2UpdateForUnsupportedProductId() {
        ArrayList<String> lines = new ArrayList<>();
        lines.add("{\"type\":\"l2update\",\"product_id\":\"USD-ETH\",\"changes\":[[\"sell\",\"2.0\",\"3.0\"]],\"time\":\"2020-07-12T00:24:17.918768Z\"}");

        StatsDemo app = new StatsDemo();
        app.lines = lines;
        app.process();

        helpAssertStats(app, 0, 0, null, null, null, null);
    }

    public void testL2UpdateWhereNoChangesField() {
        ArrayList<String> lines = new ArrayList<>();
        lines.add("{\"type\":\"l2update\",\"product_id\":\"BTC-USD\",\"time\":\"2020-07-12T00:24:17.918768Z\"}");

        StatsDemo app = new StatsDemo();
        app.lines = lines;
        app.process();

        helpAssertStats(app, 0, 0, null, null, null, null);
    }

    public void testL2UpdateWhereChangesFieldIsEmptyArray() {
        ArrayList<String> lines = new ArrayList<>();
        lines.add("{\"type\":\"l2update\",\"product_id\":\"BTC-USD\",\"changes\":[],\"time\":\"2020-07-12T00:24:17.918768Z\"}");

        StatsDemo app = new StatsDemo();
        app.lines = lines;
        app.process();

        helpAssertStats(app, 0, 0, null, null, null, null);
    }

    public void testL2UpdateWhereChangesHasMultipleEntries() {
        ArrayList<String> lines = new ArrayList<>();
        lines.add("{\"type\":\"l2update\",\"product_id\":\"BTC-USD\",\"changes\":[[\"buy\",\"1.0\",\"2.0\"], [\"sell\",\"3.0\",\"4.0\"], [\"buy\",\"1.0\",\"5.0\"], [\"sell\",\"3.0\",\"6.0\"]],\"time\":\"2020-07-12T00:24:17.918768Z\"}");

        StatsDemo app = new StatsDemo();
        app.lines = lines;
        app.process();

        helpAssertStats(app, 1, 1, 3.0, 3.0, 1.0, 1.0);

        Object[] entriesArray = app.asksMap.entrySet().toArray();
        helpAssertEntry(entriesArray, "ask", 0, 3.0, 6.0, 0.0, 0.0);

        entriesArray = app.bidsMap.descendingMap().entrySet().toArray();
        helpAssertEntry(entriesArray, "bid", 0, 1.0, 5.0, 0.0, 0.0);
    }

    public void testL2Updates() {
        ArrayList<String> lines = new ArrayList<>();
        lines.add("{\"type\":\"l2update\",\"product_id\":\"BTC-USD\",\"changes\":[[\"sell\",\"2.0\",\"3.0\"]],\"time\":\"2020-07-12T00:24:17.918768Z\"}");
        lines.add("{\"type\":\"l2update\",\"product_id\":\"BTC-USD\",\"changes\":[[\"sell\",\"2.0\",\"0.0\"]],\"time\":\"2020-07-12T00:24:17.930414Z\"}");
        lines.add("{\"type\":\"l2update\",\"product_id\":\"BTC-USD\",\"changes\":[[\"sell\",\"3.0\",\"2.0\"]],\"time\":\"2020-07-12T00:24:17.930414Z\"}");
        lines.add("{\"type\":\"l2update\",\"product_id\":\"BTC-USD\",\"changes\":[[\"buy\",\"1.0\",\"4.0\"]],\"time\":\"2020-07-12T00:24:17.957274Z\"}");
        // sell 3,2
        // buy 1,4

        StatsDemo app = new StatsDemo();
        app.lines = lines;
        app.process();

        helpAssertStats(app, 1, 1, 3.0, 3.0, 1.0, 1.0);

        Object[] entriesArray = app.asksMap.entrySet().toArray();
        helpAssertEntry(entriesArray, "ask", 0, 3.0, 2.0, 0.0, 0.0);

        entriesArray = app.bidsMap.descendingMap().entrySet().toArray();
        helpAssertEntry(entriesArray, "bid", 0, 1.0, 4.0, 0.0, 0.0);
    }

    public void testFinalStats() {
        StatsDemo app = new StatsDemo();
        app.file = "data/messages1";
        app.runCount = 1;
        app.process();

        assertEquals("total asks count", 35, app.asksMap.entrySet().size());
        assertEquals("total bids count", 72, app.bidsMap.entrySet().size());

        // are the midpoint prices for both sides what we expect?
        assertEquals("ask midpoint 1 correct", 9228.69411643, app.topSideMidpointPrice1.get("ask"), 0.000000005); //TODO non-ideal compare
        assertEquals("ask midpoint 2 correct", 9228.70,       app.topSideMidpointPrice2.get("ask"));
        assertEquals("bid midpoint 1 correct", 9228.62588393, app.topSideMidpointPrice1.get("bid"), 0.000000005);//TODO non-ideal compare
        assertEquals("bid midpoint 2 correct", 9228.62,       app.topSideMidpointPrice2.get("bid"));

        //int topCount = Math.min(entriesArray.length, 5); // to gracefully handle case where there are less than 5 price levels

        // are the top 5 ask levels what we expect?
        Object[] entriesArray = app.asksMap.entrySet().toArray();
        helpAssertEntry(entriesArray, "ask", 0, 9228.670000, 3009.558010, 0.000000005, 0.00000006);//TODO non-ideal compare
        helpAssertEntry(entriesArray, "ask", 1, 9228.680000, 4771.000000, 0.000000005, 0.0);
        helpAssertEntry(entriesArray, "ask", 2, 9228.690000, 6020.000000, 0.000000005, 0.0);
        helpAssertEntry(entriesArray, "ask", 3, 9228.700000, 6989.000000, 0.000000005, 0.0);
        helpAssertEntry(entriesArray, "ask", 4, 9228.710000, 7781.000000, 0.000000005, 0.0);

        // are the top 5 bid levels what we expect?
        entriesArray = app.bidsMap.descendingMap().entrySet().toArray();
        helpAssertEntry(entriesArray, "bid", 0, 9228.650000, 3009.996000, 0.000000005, 0.0);
        helpAssertEntry(entriesArray, "bid", 1, 9228.640000, 4771.000000, 0.000000005, 0.0);
        helpAssertEntry(entriesArray, "bid", 2, 9228.630000, 6020.000000, 0.000000005, 0.0);
        helpAssertEntry(entriesArray, "bid", 3, 9228.620000, 6989.000000, 0.000000005, 0.0);
        helpAssertEntry(entriesArray, "bid", 4, 9228.610000, 7781.000000, 0.000000005, 0.0);
    }

    void helpAssertStats(StatsDemo app, int asksCount, int bidsCount, Double askMidpoint1, Double askMidpoint2, Double bidMidpoint1, Double bidMidpoint2) {
        assertEquals("total asks count", asksCount, app.asksMap.entrySet().size());
        assertEquals("total bids count", bidsCount, app.bidsMap.entrySet().size());

        assertEquals("ask midpoint 1 correct", askMidpoint1, app.topSideMidpointPrice1.get("ask"));
        assertEquals("ask midpoint 2 correct", askMidpoint2, app.topSideMidpointPrice2.get("ask"));
        assertEquals("bid midpoint 1 correct", bidMidpoint1, app.topSideMidpointPrice1.get("bid"));
        assertEquals("bid midpoint 2 correct", bidMidpoint2, app.topSideMidpointPrice2.get("bid"));
    }

    void helpAssertEntry(Object[] entriesArray, String side, int index, double price, double volume, double priceDelta, double volumeDelta) {
        Map.Entry<Double, Double> entry = (Map.Entry<Double, Double>) entriesArray[index];
        assertEquals(String.format("price matches (%1$s, %2$d)",side,index), price, entry.getKey(), priceDelta);
        assertEquals(String.format("volume matches (%1$s, %2$d)",side,index), volume, entry.getValue(), volumeDelta);
    }
}