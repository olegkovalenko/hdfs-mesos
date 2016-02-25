package net.elodina.mesos.hdfs;

import com.google.protobuf.ByteString;
import net.elodina.mesos.hdfs.Util.Period;
import net.elodina.mesos.hdfs.Util.Str;
import org.apache.log4j.*;
import org.apache.mesos.MesosSchedulerDriver;
import org.apache.mesos.Protos;
import org.apache.mesos.SchedulerDriver;

import java.io.File;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.apache.mesos.Protos.Filters;
import static org.apache.mesos.Protos.TaskID;
import static org.apache.mesos.Protos.TaskInfo;

public class Scheduler implements org.apache.mesos.Scheduler {
    public static final Scheduler $ = new Scheduler();
    private static final Logger logger = Logger.getLogger(Scheduler.class);

    public final Config config = new Config();
    private SchedulerDriver driver;

    @Override
    public void registered(SchedulerDriver driver, Protos.FrameworkID id, Protos.MasterInfo master) {
        logger.info("[registered] framework:" + Str.id(id.getValue()) + " master:" + Str.master(master));
        this.driver = driver;

        Nodes.frameworkId = id.getValue();
        Nodes.save();
    }

    @Override
    public void reregistered(SchedulerDriver driver, Protos.MasterInfo master) {
        logger.info("[reregistered] " + Str.master(master));
        this.driver = driver;
    }

    @Override
    public void resourceOffers(SchedulerDriver driver, List<Protos.Offer> offers) {
        logger.info("[resourceOffers]\n" + Str.offers(offers));
        onOffers(offers);
    }

    @Override
    public void offerRescinded(SchedulerDriver driver, Protos.OfferID id) {
        logger.info("[offerRescinded] " + Str.id(id.getValue()));
    }

    @Override
    public void statusUpdate(SchedulerDriver driver, Protos.TaskStatus status) {
        logger.info("[statusUpdate] " + Str.status(status));
    }

    @Override
    public void frameworkMessage(SchedulerDriver driver, Protos.ExecutorID executorId, Protos.SlaveID slaveId, byte[] data) {
        logger.info("[frameworkMessage] executor:" + Str.id(executorId.getValue()) + ", slave: " + Str.id(slaveId.getValue()) + ", data: " + new String(data));
    }

    @Override
    public void disconnected(SchedulerDriver driver) {
        logger.info("[disconnected]");
        this.driver = null;
    }

    @Override
    public void slaveLost(SchedulerDriver driver, Protos.SlaveID id) {
        logger.info("[slaveLost] " + Str.id(id.getValue()));
    }

    @Override
    public void executorLost(SchedulerDriver driver, Protos.ExecutorID executorId, Protos.SlaveID slaveId, int status) {
        logger.info("[executorLost] executor:" + Str.id(executorId.getValue()) + ", slave: " + Str.id(slaveId.getValue()) + ", status: " + status);
    }

    @Override
    public void error(SchedulerDriver driver, String message) {
        logger.info("[error] " + message);
    }

    private void onOffers(List<Protos.Offer> offers) {
        // start nodes
        for (Protos.Offer offer : offers) {
            String reason = acceptOffer(offer);

            if (reason != null) {
                logger.info("Declined offer " + Str.offer(offer) + ":\n" + reason);
                driver.declineOffer(offer.getId());
            }
        }

        // stop nodes
        for (Node node : Nodes.getNodes()) {
            if (node.state != Node.State.STOPPING) continue;

            if (node.runtime == null) {
                node.state = Node.State.IDLE;
                continue;
            }

            if (!node.runtime.killSent) {
                driver.killTask(TaskID.newBuilder().setValue(node.runtime.taskId).build());
                node.runtime.killSent = true;
            }
        }

        Nodes.save();
    }

    private String acceptOffer(Protos.Offer offer) {
        List<Node> nodes = new ArrayList<>();
        for (Node node : Nodes.getNodes())
            if (node.state == Node.State.STARTING && node.runtime == null)
                nodes.add(node);

        if (nodes.isEmpty()) return "nothing to start";

        List<String> reasons = new ArrayList<>();
        for (Node node : nodes) {
            String reason = node.matches(offer);
            if (reason != null) reasons.add("node " + node.id + ": " + reason);
            else {
                launchTask(node, offer);
                return null;
            }
        }

        return Util.join(reasons, ", ");
    }

    private void launchTask(Node node, Protos.Offer offer) {
        node.initRuntime(offer);
        TaskInfo task = node.newTask();

        driver.launchTasks(Arrays.asList(offer.getId()), Arrays.asList(task), Filters.newBuilder().setRefuseSeconds(1).build());
        logger.info("Starting node " + node.id + " with task " + node.runtime.taskId + " for offer " + offer.getId().getValue());

        node.state = Node.State.RUNNING;
    }

    public void run() {
        initLogging();
        logger.info("Starting " + getClass().getSimpleName() + ":\n" + config);

        config.resolveDeps();
        Nodes.load();

        final HttpServer server = new HttpServer();
        try { server.start(); }
        catch (Exception e) { throw new RuntimeException(e); }

        Protos.FrameworkInfo.Builder frameworkBuilder = Protos.FrameworkInfo.newBuilder();
        if (Nodes.frameworkId != null) frameworkBuilder.setId(Protos.FrameworkID.newBuilder().setValue(Nodes.frameworkId));
        frameworkBuilder.setUser(config.user != null ? config.user : "");

        frameworkBuilder.setName(config.frameworkName);
        frameworkBuilder.setRole(config.frameworkRole);
        frameworkBuilder.setFailoverTimeout(config.frameworkTimeout.ms() / 1000);
        frameworkBuilder.setCheckpoint(true);

        Protos.Credential.Builder credsBuilder = null;
        if (config.principal != null && config.secret != null) {
            frameworkBuilder.setPrincipal(config.principal);

            credsBuilder = Protos.Credential.newBuilder();
            credsBuilder.setPrincipal(config.principal);
            credsBuilder.setSecret(ByteString.copyFromUtf8(config.secret));
        }

        MesosSchedulerDriver driver;
        if (credsBuilder != null) driver = new MesosSchedulerDriver(Scheduler.$, frameworkBuilder.build(), config.master, credsBuilder.build());
        else driver = new MesosSchedulerDriver(Scheduler.$, frameworkBuilder.build(), config.master);

        Runtime.getRuntime().addShutdownHook(new Thread() {
            public void run() {
                logger.info("Stopping " + getClass().getSimpleName());

                try { server.stop(); }
                catch (Exception e) { logger.warn("", e); }
            }
        });

        Protos.Status status = driver.run();
        System.exit(status == Protos.Status.DRIVER_STOPPED ? 0 : 1);
    }

    void initLogging() {
        BasicConfigurator.resetConfiguration();

        Logger root = Logger.getRootLogger();
        root.setLevel(Level.INFO);

        PatternLayout layout = new PatternLayout("%d [%t] %-5p %c %x - %m%n");
        root.addAppender(new ConsoleAppender(layout));
    }

    public static class Config {
        public String api = "http://localhost:7000";

        public File jar;
        public File hadoop;

        public int apiPort() {
            try {
                int port = new URI(api).getPort();
                return port == -1 ? 80 : port;
            } catch (URISyntaxException e) {
                throw new IllegalStateException(e);
            }
        }

        public String master;
        public String principal;
        public String secret;
        public String user;

        public String frameworkName = "hdfs";
        public String frameworkRole = "*";
        public Period frameworkTimeout = new Period("30d");

        void resolveDeps() {
            String hadoopMask = "hadoop-.*gz";
            hadoop = Util.IO.findFile(new File("."), hadoopMask);
            if (hadoop == null) throw new IllegalStateException(hadoopMask + " not found in current dir");

            String jarMask = "hdfs-mesos-.*jar";
            jar = Util.IO.findFile(new File("."), jarMask);
            if (jar == null) throw new IllegalStateException(jarMask + " not found in current dir");
        }
    }
}
