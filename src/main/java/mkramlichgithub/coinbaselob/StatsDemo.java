package mkramlichgithub.coinbaselob;

import java.io.*;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.*;
import javax.websocket.*;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;

import org.json.JSONArray;
import org.json.JSONObject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ClientEndpoint
public class StatsDemo {

    static Logger log = LoggerFactory.getLogger(StatsDemo.class);

    String inputSource = "ws"; // supported values: ws, file, lines
    String file = null;        // only used if inputSource is file
    int runCount = 1;          // only used if inputSource is file; runs a loop of the messages file N times (0 means 0; -1 means loop forever)
    String urlEnv = "prod";    // supported values: prod, sandbox
    String url = null;         // if URL arg set here it will be used, otherwise connect will lookup urls[urlEnv]
    ArrayList<String> lines;   // used by tests to inject an artificial array of string message lines (handling-wise equiv to file and ws mode)
    private final Map<String,String> urls = new HashMap<String,String>();
    private Session session;
    final TreeMap<Double, Double> asksMap = new TreeMap<Double, Double>();
    final TreeMap<Double, Double> bidsMap = new TreeMap<Double, Double>();
    final Map<String,Double> topSideMidpointPrice1 = new HashMap<String,Double>(); // keys: ask, bid
    final Map<String,Double> topSideMidpointPrice2 = new HashMap<String,Double>(); // keys: ask, bid

    public StatsDemo() {
        urls.put("sandbox","wss://ws-feed-public.sandbox.pro.coinbase.com");
        urls.put("prod",   "wss://ws-feed.pro.coinbase.com");
    }

    void process() {
        if ((this.url != null) || (this.urlEnv != null)) {
            this.inputSource = "ws";
        }

        if (this.file != null) {
            this.inputSource = "file";
        }

        if (this.lines != null) {
            this.inputSource = "lines";
        }

        log.info(String.format("inputSource: %1$s", this.inputSource));
        log.info(String.format("url: %1$s",         this.url));

        switch (this.inputSource) {
            case "lines": processLines();     break;
            case "file":  processFile();      break;
            case "ws":    processWebSocket(); break;
        }
    }

    private void processLines() {
        log.info(String.format("will process messages from injected lines field: %1$s", this.lines));
        for (String line: this.lines) {
            onMessage(line);
        }
    }

    private void processFile() {
        log.info(String.format("will process messages from file: %1$s", this.file));
        try {
            for (int n=0; (n < this.runCount) || (this.runCount == -1); n++) { // -1 means infinity: repeat replaying the file forever
                log.info(String.format("starting file run %1$d of %2$s", n+1, (this.runCount==-1?"infinity":Integer.toString(this.runCount))));
                FileReader fr = new FileReader(this.file);
                BufferedReader br = new BufferedReader(fr);
                while (br.ready()) {
                    String line = br.readLine();
                    onMessage(line);
                }
            }
        } catch (IOException e) {
            e.printStackTrace(); // TODO log
        }
    }

    private void processWebSocket() {
        try {
            connect();
            log.info("hit ^C to quit (interrupt)");
            // following is to keep the process alive (no main thread exit) so it can continue to receive & act on WS messages from Coinbase
            synchronized (this) { // TODO non-ideal lock subject (because also a registered WS client callback target)
                try {
                    this.wait();
                } catch (InterruptedException ex) {
                    ex.printStackTrace(); // TODO log
                }
            }
        } catch (DeploymentException | IOException | URISyntaxException e) { // TODO yuck
            e.printStackTrace(); // TODO log
        }
    }

    public void connect() throws DeploymentException, IOException, URISyntaxException {
        try {
            WebSocketContainer container = ContainerProvider.getWebSocketContainer();
            //container.setDefaultMaxBinaryMessageBufferSize(1024 * 1024); // TODO understand buffer effects & options thoroughly; test, maybe tune
            //container.setDefaultMaxTextMessageBufferSize(1024 * 1024);
            if (this.url == null) {
                this.url = this.urls.get(this.urlEnv);
            }
            log.info(String.format("connecting to: %1$s", this.url));
            container.connectToServer(this, new URI(this.url));
        } catch (Exception ex) {
            log.error(String.format("Could not connect to remote server (%1$s): %2$s", this.url, ex));
            //ex.printStackTrace();
            throw ex;
        }
    }

    @OnOpen
    public void onOpen(Session session) {
        log.info("opened WS");
        this.session = session; // tracking to be proper but not strictly being used anywhere yet
        //System.out.println(session);

        String subscribe =
            "{\"type\": \"subscribe\"," +
             "\"product_ids\": [\"BTC-USD\"],"+
             "\"channels\": ["+
                 "\"heartbeat\","+ // TODO not being handled/tracked currently so not needed, but helpful to see during analysis
                 //"\"status\","+
                 "\"level2\"]}";
                 //"\"level2\","+
                 //"{\"name\":\"ticker\",\"product_ids\":[\"BTC-USD\"]}]}";
        try {
            log.debug("sending subscribe message: "+subscribe);
            this.session.getBasicRemote().sendText(subscribe);
        } catch (IOException ex) {
            log.error("send of subscribe message failed:");
            ex.printStackTrace(); // TODO log
        }
    }

    @OnClose
    public void onClose(CloseReason reason) {
        log.info("closing WS: " + reason);
        this.session = null;
    }

    @OnError
    public void onError(Throwable t) {
        log.error("error on WS: " + t); // TODO logged right?
    }

    @OnMessage
    public void onMessage(String message) {
        log.debug(message);
        JSONObject jo = new JSONObject(message);
        String type = jo.getString("type");
        // message types known or seen during dev: error, subscriptions, status, snapshot, l2update, heartbeat, ticker
        log.debug("type: " + type);

        if (type.equals("snapshot")) {
            this.handle_snapshot(message, jo);
        } else if (type.equals("l2update")) {
            this.handle_l2update(message, jo);
        } else {
            log.warn(String.format("unhandled type: %1$s", type));
        }
    }

    public void handle_snapshot(String message, JSONObject jo) {
        log.debug("handle_snapshot()");
        // FYI example of typical sizes seen during orig dev (of snapshot's asks and bids arrays):
        // in sandbox:   34 ask levels,   70 bid levels
        // in prod:    7780 ask levels, 9810 bid levels

        String prodId = jo.getString("product_id");
        if (!"BTC-USD".equals(prodId)) {
            log.debug(String.format("snapshot product_id value was not expected (BTC-USD) so will ignore: %1$s", prodId));
        } else {
            cache_side_from_snapshot(jo, "asks", this.asksMap);
            cache_side_from_snapshot(jo, "bids", this.bidsMap);

            recalc_stats();
            update_lobstats_file();
        }
    }

    private void cache_side_from_snapshot(JSONObject jo, String side, Map<Double, Double> cache) {
        // see the file data/messages1 for example of a snapshot message seen from the sandbox feed, and data/messages2 for prod
        cache.clear(); // in case we receive multiple snapshots, ensure subsequent ones start with clean slate
        if (jo.has(side)) {
            JSONArray side_demands = jo.getJSONArray(side);
            log.debug(String.format("%1$s length: %2$d", side, side_demands.length()));
            for (int i = 0; i < side_demands.length(); i++) {
                JSONArray demand = side_demands.getJSONArray(i);
                double price = demand.getDouble(0);
                double volume = demand.getDouble(1);
                cache.put(price, volume);
                log.debug(String.format("%1$s %2$d: %3$.2f, %4$.8f", side, i, price, volume)); // TODO or trace, cuz might be huge number of these
            }
        } else {
            log.warn(String.format("JSON object lacks expected field: %1$s", side));
        }
    }

    private void recalc_stats() {
        recalc_stats_for_side("ask", this.asksMap);
        recalc_stats_for_side("bid", this.bidsMap.descendingMap()); // TODO may be perf opportunities with desc wrapper
    }

    private void recalc_stats_for_side(String side, Map<Double,Double> cache) {
        log.debug(String.format("recalc_for_side(): %1$s", side));

        int topCount = 5; // how many best (lowest price) asks or best (highest price) bids are considered to form the top of the book
        double totalVolume = 0.0; // of the topCount asks or bids
        double pv = 0.0; // the sum of price*volume for all of the top asks or bids

        Set<Map.Entry<Double,Double>> entries = cache.entrySet();
        Object[] entriesArray = entries.toArray();
        topCount = Math.min(entriesArray.length, topCount); // to gracefully handle case where there are less than 5 price levels
        log.debug(String.format("top %1$d %2$ss:", topCount, side));
        for (int i = 0; i < topCount; i++) {
            Map.Entry<Double,Double> entry = (Map.Entry<Double, Double>) entriesArray[i];
            double price = entry.getKey();
            double volume = entry.getValue();
            //System.out.printf("%1$.2f %2$.8f\n", entry.getKey(), entry.getValue());
            totalVolume += volume;
            pv += price * volume;
            log.debug(String.format("top %1$s %2$d: %3$.2f, %4$.8f", side, i, price, volume));
        }

        // calc the midpoint via method 1 (mean-ish):
        if (totalVolume <= 0.0) {
            log.debug(String.format("wont calc midpoint1 (mean) due to insuff total volume: %1$s, %2$.8f", side, totalVolume));
        } else {
            double midpointPrice1 = pv / totalVolume;
            this.topSideMidpointPrice1.put(side, midpointPrice1);
            log.debug(String.format("best midpoint1 (mean):   %1$s, %2$.8f", side, midpointPrice1));
        }

        // calc the midpoint via method 2 (median-ish; which I believe is closer to the intent of the biz req):
        double volumeMid = totalVolume / 2;
        double volumeCum = 0.0;
        if ((topCount < 0) || (totalVolume < 0.0)) {
            log.debug(String.format("wont calc midpoint2 (median) due to insuff topCount or totalVolume: %1$s, %2%d, %3$.8f", side, topCount, totalVolume));
        } else {
            for (int i = 0; i < topCount; i++) {
                Map.Entry<Double, Double> entry = (Map.Entry<Double, Double>) entriesArray[i];
                double price = entry.getKey();
                double volume = entry.getValue();
                // TODO its important to nail the range inclusion logic for "median"; in terms of what users expect ideally/traditionally; to confirm in regression tests; and to communicate *exactly* in our public docs; if any expression is wrong then adjust accordingly!
                if (i > 0) {
                    Map.Entry<Double, Double> priorEntry = (Map.Entry<Double, Double>) entriesArray[i - 1];
                    double priorVolume = priorEntry.getValue();
                    volumeCum += priorVolume;
                }
                if ((volumeMid >= volumeCum) && (volumeMid < (volumeCum + volume))) { // if in the expected range
                    double midpointPrice2 = price;
                    this.topSideMidpointPrice2.put(side, midpointPrice2);
                    log.debug(String.format("best %1$s midpoint2 (median): %2$.2f", side, midpointPrice2));
                    break; // out of surrounding for
                }
            }
        }
    }

    public void handle_l2update(String message, JSONObject jo) {
        log.debug(String.format("handling l2update: %1$s", message));

        // message examples:
        // {"type":"l2update","product_id":"BTC-USD","changes":[["sell","9289.70","0.00000000"]],"time":"2020-07-12T00:24:17.918768Z"}
        // {"type":"l2update","product_id":"BTC-USD","changes":[["sell","9286.38","0.02757568"]],"time":"2020-07-12T00:24:17.930414Z"}
        // {"type":"l2update","product_id":"BTC-USD","changes":[["buy","9250.53","0.00000000"]],"time":"2020-07-12T00:24:17.957274Z"}

        String prodId = jo.getString("product_id");
        if (!"BTC-USD".equals(prodId)) {
            log.debug(String.format("l2update product_id value was not expected (BTC-USD) so will ignore: %1$s", prodId));
        } else if (!jo.has("changes")) {
            log.debug(String.format("l2update has no changes field so wont update cache, recalc stats or update lobstats file: %1$s", message));
        } else {
            JSONArray changes = jo.getJSONArray("changes");
            // update our cache of asks and bids:
            for (int i = 0; i < changes.length(); i++) {
                JSONArray change = changes.getJSONArray(i);
                String side = change.getString(0);
                double price = change.getDouble(1);
                double volume = change.getDouble(2);

                if (side.equals("sell")) {
                    update_side_cache(price, volume, this.asksMap);
                } else if (side.equals("buy")) {
                    update_side_cache(price, volume, this.bidsMap);
                }
            }
            recalc_stats(); // TODO may be opportunity to skip unnecessary recalcs to optimize perf
            update_lobstats_file(); // TODO ditto above, except no need to regen file if displayed stats haven't changed, etc.
        }
    }

    private void update_side_cache(double price, double volume, Map<Double, Double> cache) {
        if (volume == 0) { // there is no remaining BTC demand at this price level, on this side (ask or bid), so remove from our LOB cache
            cache.remove(price);
        } else {
            cache.put(price,volume);
        }
    }

    private void update_lobstats_file() {
        try {
            FileWriter file = new FileWriter("lobstats");
            file.write(get_lobstats_report_for_side("ask", this.asksMap));
            file.write("\n");
            file.write(get_lobstats_report_for_side("bid", this.bidsMap.descendingMap())); // TODO may be perf opportunities with desc wrapper
            file.close();
            log.debug("updated lobstats file");
        } catch (IOException e) {
            log.error("error updating the lobstats file");
            e.printStackTrace(); // TODO log
        }
    }

    String get_lobstats_report_for_side(String side, Map<Double,Double> cache) {
        log.debug(String.format("get_lobstats_report_for_side(%1$s,...)", side));

        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);

        Double midpointPrice1 = this.topSideMidpointPrice1.get(side);
        Double midpointPrice2 = this.topSideMidpointPrice2.get(side);

        String priceBlurb = (midpointPrice1 != null) ? String.format("%1$.8f", midpointPrice1) : "NONE";
        pw.printf("top %1$s midpoint1 (mean):   %2$s\n", side, priceBlurb);

        priceBlurb = (midpointPrice2 != null) ? String.format("%1$.2f", midpointPrice2) : "NONE";
        pw.printf("top %1$s midpoint2 (median): %2$s\n", side, priceBlurb);

        Set<Map.Entry<Double,Double>> entries = cache.entrySet();
        Object[] entriesArray = entries.toArray();
        pw.printf("total %1$s count: %2$d\n", side, entriesArray.length);

        int topCount = Math.min(entriesArray.length, 5); // to gracefully handle case where there are less than 5 price levels
        //pw.printf("top %1$d %2$ss:\n", topCount, side);
        for (int i = 0; i < topCount; i++) {
            Map.Entry<Double, Double> entry = (Map.Entry<Double, Double>) entriesArray[i];
            double price = entry.getKey();
            double volume = entry.getValue();
            pw.printf("%1$f %2$f\n", price, volume);
        }
        pw.close();
        return sw.toString();
    }

    public static void main(String[] args) {
        log.info("Coinbase LOB Stats Demo, by Mike Kramlich");

        Options options = new Options();
        options.addOption(new Option("f", "file",     true, "file source type filename"));
        options.addOption(new Option("r", "runcount", true, "loopable runcount for file source type"));
        options.addOption(new Option("u", "url",      true, "URL of Coinbase Pro compat WS feed endpoint"));
        options.addOption(new Option("e", "env",      true, "env of Coinbase Pro WS feed endpoint (prod or sandbox)"));

        CommandLineParser parser = new DefaultParser();
        HelpFormatter formatter = new HelpFormatter();

        StatsDemo app = new StatsDemo();

        log.info("args: " + Arrays.toString(args));
        for (String arg : args) log.info(String.format("arg: %1$s", arg));

        if (args.length > 0) {
            try {
                CommandLine cmd = parser.parse(options, args);

                String file = cmd.getOptionValue("file");
                if (file != null) app.file = file;

                String runCount = cmd.getOptionValue("runcount");
                if (runCount != null) app.runCount = Integer.parseInt(runCount);

                String url = cmd.getOptionValue("url");
                if (url != null) app.url = url;

                String env = cmd.getOptionValue("env");
                if (env != null) app.urlEnv = env;

                log.info(String.format("file: %1$s",     app.file));
                log.info(String.format("runcount: %1$s", app.runCount));
                log.info(String.format("url: %1$s",      app.url));
                log.info(String.format("env: %1$s",      app.urlEnv));
            } catch (ParseException | NumberFormatException e) {
                e.printStackTrace(); // TODO log
                log.error(e.getMessage());
                formatter.printHelp("StatsDemo", options); // TODO log
                System.exit(1);
            }
        }

        app.process();
    }
}
