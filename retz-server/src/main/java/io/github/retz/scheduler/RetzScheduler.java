/**
 *    Retz
 *    Copyright (C) 2016-2017 Nautilus Technologies, Inc.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */
package io.github.retz.scheduler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import io.github.retz.cli.TimestampHelper;
import io.github.retz.db.Database;
import io.github.retz.mesosc.MesosHTTPFetcher;
import io.github.retz.misc.LogUtil;
import io.github.retz.planner.*;
import io.github.retz.planner.spi.Resource;
import io.github.retz.protocol.data.Job;
import io.github.retz.protocol.data.ResourceQuantity;
import io.github.retz.protocol.exception.JobNotFoundException;
import io.github.retz.web.StatusCache;
import org.apache.mesos.Protos;
import org.apache.mesos.Scheduler;
import org.apache.mesos.SchedulerDriver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class RetzScheduler implements Scheduler {
    public static final String FRAMEWORK_NAME = "Retz-Framework";
    public static final String HTTP_SERVER_NAME;
    private static final Logger LOG = LoggerFactory.getLogger(RetzScheduler.class);

    static {
        // TODO: stop hard coding and get the file name in more generic way
        // COMMENT: I put the trick in build.gradle, saving the exact jar file name as resource bundle
        // REVIEW: http://www.eclipse.org/aether/ (not surveyed yet)
        // REVIEW: https://github.com/airlift/resolver (used in presto?)
        ResourceBundle labels = ResourceBundle.getBundle("retz-server");

        HTTP_SERVER_NAME = labels.getString("servername");
        LOG.info("Server name in HTTP(S) header: {}", HTTP_SERVER_NAME);
    }

    private final ResourceQuantity maxJobSize;
    private final Long maxFileSize;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Map<String, Protos.Offer> offerStock = new ConcurrentHashMap<>();
    private final Planner planner;
    private final Protos.Filters filters;
    private Launcher.Configuration conf;
    private Protos.FrameworkInfo frameworkInfo;
    private Map<String, List<Protos.SlaveID>> slaves;
    private Optional<String> master;

    public RetzScheduler(Launcher.Configuration conf, Protos.FrameworkInfo frameworkInfo) throws Throwable {
        objectMapper.registerModule(new Jdk8Module());
        planner = PlannerFactory.create(conf.getServerConfig().getPlannerName(), conf.getServerConfig());
        this.conf = Objects.requireNonNull(conf);
        this.frameworkInfo = frameworkInfo;
        this.slaves = new ConcurrentHashMap<>();
        this.filters = Protos.Filters.newBuilder().setRefuseSeconds(conf.getServerConfig().getRefuseSeconds()).build();
        maxJobSize = conf.getServerConfig().getMaxJobSize();
        maxFileSize = conf.getServerConfig().getMaxFileSize();
        this.master = Optional.empty();
    }

    @Override
    public void disconnected(SchedulerDriver driver) {
        Optional<String> prevMaster = this.master;
        this.master = Optional.empty();
        StatusCache.voidMaster();
        LOG.warn("Disconnected from cluster (previous master={})", prevMaster);
    }

    @Override
    public void error(SchedulerDriver driver, String message) {
        LOG.error(message);
        // 'Framework has been removed' comes here;
    }

    @Override
    public void frameworkMessage(SchedulerDriver driver, Protos.ExecutorID executorId, Protos.SlaveID slaveId, byte[] data) {
        LOG.info("Framework Message ({} bytes)", data.length);
    }

    @Override
    public void offerRescinded(SchedulerDriver driver, Protos.OfferID offerId) {
        LOG.info("Offer rescinded: {}", offerId.getValue());
        // Hereby offers must be removed from offerStock, instead it's removed at slaveLost() callback.
        // This is based on an assumption that all offerRescinded calls come with slaveLost().
    }

    @Override
    public void registered(SchedulerDriver driver, Protos.FrameworkID frameworkId, Protos.MasterInfo masterInfo) {
        try {
            registered0(driver, frameworkId, masterInfo);
        } catch (IOException e) {
            LogUtil.error(LOG, "RetzScheduler.registered() failed", e);
        }
    }

    private void registered0(SchedulerDriver driver, Protos.FrameworkID frameworkId, Protos.MasterInfo masterInfo) throws IOException {
        if (!validMesosVersion(masterInfo.getVersion())) {
            // TODO: if the master is in maintenance period, Retz does not abort but sleep and retry later?
            driver.abort();
            return;
        }

        String newMaster = new StringBuilder()
                .append(masterInfo.getHostname())
                .append(":")
                .append(masterInfo.getPort())
                .toString();

        LOG.info("Connected to master {} version={}; Framework ID: {}",
                newMaster, masterInfo.getVersion(), frameworkId.getValue());
        this.master = Optional.of(newMaster);
        StatusCache.updateMaster(newMaster);
        frameworkInfo = frameworkInfo.toBuilder().setId(frameworkId).build();

        Optional<String> oldFrameworkId = Database.getInstance().getFrameworkId();
        if (oldFrameworkId.isPresent()) {
            if (oldFrameworkId.get().equals(frameworkId.getValue())) {
                // framework exists. nothing to do
                LOG.info("Framework id={} existed in past. Recovering any running jobs...", frameworkId.getValue());
                reconcileAllRunningJobs(driver);
            } else {
                LOG.error("Old different framework ({}) exists (!= {}). Quitting",
                        oldFrameworkId.get(), frameworkId.getValue());
                driver.stop();
            }
        } else {
            if (!Database.getInstance().setFrameworkId(frameworkId.getValue())) {
                LOG.warn("Failed to remember frameworkID...");
            }
        }
    }

    protected boolean validMesosVersion(String version) {
        if (version != null) {
            if (version.startsWith("1.2") || version.startsWith("1.3") || version.startsWith("1.4")) {
                return true;
            }
        }
        LOG.error("Unsupported Mesos version {}, should be one of [1.2, 1.3, 1.4]", version);
        return false;
    }

    @Override
    public void reregistered(SchedulerDriver driver, Protos.MasterInfo masterInfo) {
        try {
            reregistered0(driver, masterInfo);
        } catch (IOException e) {
            LogUtil.error(LOG, "RetzScheduler.reregistered() failed", e);
        }
    }

    private void reregistered0(SchedulerDriver driver, Protos.MasterInfo masterInfo) throws IOException {
        // Maybe long time split brain, recovering all states from master required.

        if (!validMesosVersion(masterInfo.getVersion())) {
            driver.abort();
            return;
        }

        String newMaster = new StringBuilder()
                .append(masterInfo.getHostname())
                .append(":")
                .append(masterInfo.getPort())
                .toString();
        this.master = Optional.of(newMaster);
        StatusCache.updateMaster(newMaster);
        LOG.info("Reconnected to master {}", newMaster);
        reconcileAllRunningJobs(driver);
    }

    @Override
    public void resourceOffers(SchedulerDriver driver, List<Protos.Offer> offers) {
        LOG.debug("Resource offer: {}", offers.size());

        // Merge fresh offers from Mesos and offers in stock here, declining duplicate offers
        Stanchion.schedule(() -> {
            List<Protos.Offer> available = new ArrayList<>();
            synchronized (offerStock) {
                // TODO: cleanup this code, optimize for max.stock = 0 case
                Map<String, List<Protos.Offer>> allOffers = new HashMap<>();
                for (Protos.Offer offer : offerStock.values()) {
                    String key = offer.getSlaveId().getValue();
                    List<Protos.Offer> list = allOffers.computeIfAbsent(key, k -> new ArrayList<>());
                    list.add(offer);
                }
                for (Protos.Offer offer : offers) {
                    String key = offer.getSlaveId().getValue();
                    List<Protos.Offer> list = allOffers.computeIfAbsent(key, k -> new ArrayList<>());
                    list.add(offer);
                }

                int declined = 0;
                for (Map.Entry<String, List<Protos.Offer>> e : allOffers.entrySet()) {
                    if (e.getValue().size() == 1) {
                        available.add(e.getValue().get(0));
                    } else {
                        for (Protos.Offer dup : e.getValue()) {
                            driver.declineOffer(dup.getId(), filters);
                            declined += 1;
                        }
                    }
                }
                if (conf.fileConfig.getMaxStockSize() > 0) {
                    LOG.info("Offer stock renewal: {} offers available ({} declined from stock)", available.size(), declined);
                }
                offerStock.clear();
            }

            final List<Job> jobs;
            switch (conf.getServerConfig().getJobQueueType()) {
                case FIT:
                    ResourceQuantity total = new ResourceQuantity();
                    for (Protos.Offer offer : available) {
                        LOG.debug("offer: {}", offer);
                        Resource resource = ResourceConstructor.decode(offer.getResourcesList());
                        total.add(resource.toQuantity());
                    }
                    total.setNodes(offers.size());
                    // TODO: change findFit to consider not only CPU and Memory, but GPUs and Ports
                    jobs = JobQueue.findFit(planner.orderBy(), total);
                    LOG.debug("found {} jobs fit for {}", jobs.size(), total.toString());
                    break;
                case ALL:
                    jobs = JobQueue.findAll(planner.orderBy(), conf.getServerConfig().getJobQueueAllLimit());
                    LOG.debug("found {} / {} jobs", jobs.size(), conf.getServerConfig().getJobQueueAllLimit());
                    break;
                default:
                    throw new AssertionError("unknown job queue type");
            }
            handleAll(available, jobs, driver);
            // As this section is whole serialized by Stanchion, it is safe to do fetching jobs
            // from database and updating database state change from queued => starting at
            // separate transactions
        });
    }

    public void maybeInvokeNow(SchedulerDriver driver, Job job) {
        Stanchion.schedule(() -> {
            try {
                List<Job> queued = JobQueue.queued(1);
                // Make sure it's the only job in the queue - otherwise return
                // and wait in the queue
                if (!(queued.size() == 1 && queued.get(0).id() == job.id())) {
                    return;
                }
            } catch (Exception e) {
                LOG.error("maybeInvokeNow failed: {}", e.toString());
                return;
            }

            List<Protos.Offer> available = new ArrayList<>(offerStock.size());
            synchronized (offerStock) {
                available.addAll(offerStock.values());
                offerStock.clear();
            }
            // Only if the queue is empty, and with offer stock, try job invocation
            List<Job> jobs = Arrays.asList(job);
            handleAll(available, jobs, driver);
        });
    }

    public void handleAll(List<Protos.Offer> offers, List<Job> jobs, SchedulerDriver driver) throws IOException {

        // TODO: this is fleaky limitation, build this into Planner.plan as a constraint
        // Check if simultaneous jobs exceeded its limit
        int running = JobQueue.countRunning();
        if (running >= conf.fileConfig.getMaxSimultaneousJobs()) {
            LOG.warn("Number of concurrently running jobs has reached its limit: {} >= {} ({})",
                    running, conf.fileConfig.getMaxSimultaneousJobs(), ServerConfiguration.MAX_SIMULTANEOUS_JOBS);
            return;
        }

        // DO MAKE PLANNING
        List<Job> cancel = new ArrayList<>();
        List<AppJobPair> appJobPairs = planner.filter(jobs, cancel, conf.getServerConfig().useGPU());
        // update database to change all jobs state to KILLED
        JobQueue.cancelAll(cancel);

        // TODO: split pure-planning code and Mesos-related code; don't create TaskInfo and Launches here
        // TODO: unix user name is used for TaskInfo setup and not related to pure planning.
        // FIXME: TODO: this??? is definitely a tech debt!
        Plan bestPlan = planner.plan(offers, appJobPairs, conf.getServerConfig().getMaxStockSize(), conf.getServerConfig().getUserName());

        int declined = 0;
        // Accept offers from mesos
        for (OfferAcceptor acceptor : bestPlan.getOfferAcceptors()) {
            if (acceptor.getJobs().isEmpty()) {
                declined += acceptor.declineOffer(driver, filters);
            } else {
                for (Job j : acceptor.getJobs()) {
                    // Update local database, to running
                    JobQueue.starting(j, Optional.empty(), j.taskId());
                }
                acceptor.acceptOffers(driver, filters);
            }
        }
        for (Protos.Offer offer : bestPlan.getToStock()) {
            offerStock.put(offer.getSlaveId().getValue(), offer);
        }
        LOG.info("{} accepted, {} declined ({} offers back in stock)",
                bestPlan.getOfferAcceptors().stream().mapToInt(offerAcceptor -> offerAcceptor.getJobs().size()).sum(),
                declined, bestPlan.getToStock().size());

        updateOfferStats();
    }

    @Override
    public void executorLost(SchedulerDriver driver, Protos.ExecutorID executorId, Protos.SlaveID slaveId,
                             int status) {
        LOG.info("Executor {} of slave {}  stopped: {}", executorId.getValue(), slaveId.getValue(), status);

        // TODO: do we really need to manage slaves?
        List<Protos.SlaveID> mySlaves = this.slaves.get(executorId.getValue());
        if (mySlaves != null) {
            mySlaves.remove(slaveId);
            this.slaves.put(executorId.getValue(), mySlaves);
        }
    }

    // @doc Re-schedule **all** running job when a Slave is lost. I know it's a kludge.
    @Override
    public void slaveLost(SchedulerDriver driver, Protos.SlaveID slaveId) {
        LOG.warn("Slave lost: {}", slaveId.getValue());
        for (Map.Entry<String, List<Protos.SlaveID>> entry : slaves.entrySet()) {
            List<Protos.SlaveID> list = entry.getValue();
            for (Protos.SlaveID s : list) {
                if (s.getValue().equals(slaveId.getValue())) {
                    list.remove(s);
                }
            }
            slaves.put(entry.getKey(), list);
        }

        // TODO: remove **ONLY** tasks that is running on the failed slave
        Stanchion.schedule(() -> reconcileAllRunningJobs(driver));

        // There is a potential race between offerRescinded/slaveLost and using offer stocks;
        // in case handleAll trying to schedule tasks, offers are removed from offerStock
        // but being used to schedule tasks - this message can't be in time ...
        // The task with rescinded offer (slave) might fail in advance; TASK_FAILED or TASK_LOST?
        // any way in this case it should be retried...
        //
        // Clean up stocked offers from lost slave, or kept long dead
        // TODO: add tests on github #153 bug, this is a quick patch
        synchronized (offerStock) {
            Protos.Offer offer = offerStock.remove(slaveId.getValue());
            if (offer != null) {
                driver.declineOffer(offer.getId());
            }
        }
        updateOfferStats();
    }

    @Override
    public void statusUpdate(SchedulerDriver driver, Protos.TaskStatus status) {
        LOG.info("Status update of task {}: {} / {} ({})",
                status.getTaskId().getValue(), status.getState().name(), status.getMessage(),
                status.getReason());
        Stanchion.schedule(() -> {
            Optional<Job> job = JobQueue.getFromTaskId(status.getTaskId().getValue());
            if (!job.isPresent()) {
                LOG.warn("Event {} ({}) for unknown job (taskid={})",
                        status.getState().getDescriptorForType().getName(),
                        status.getMessage(), status.getTaskId().getValue());
                return;
            }

            JobStatem.Action action = JobStatem.handleCall(job.get(), status.getState());
            switch (action) {
                case FINISHED:
                    finished(status);
                    break;

                case FAILED:
                    failed(status);
                    break;

                case RETRY:
                    retry(status);
                    maybeInvokeNow(driver, job.get());
                    break;

                case NOOP:
                    break;

                case NEVER:
                    LOG.error("This cannot happen: {} {} => {}",
                            job.get().state(), status.getState().getNumber(), action);
                    throw new AssertionError("May be a state diagram (JobStatem) bug");

                case LOG:
                    LOG.warn("This cannot happen: {} {} => {}",
                            job.get().state(), status.getState().getNumber(), action);
                    break;

                case STARTED:
                    started(status);
                    break;

                case STARTING:
                    // LOG.info("Task {} starting at {}", status.getTaskId().getValue(), status.getSlaveId().getValue());
                    Optional<String> maybeUrl = Optional.empty();
                    if (this.master.isPresent()) {
                        maybeUrl = maybeGetUrl(status);
                    }
                    JobQueue.starting(job.get(), maybeUrl, status.getTaskId().getValue());
                    break;

                case KILLED: // kill by user...
                default:
                    break;
            }
        });
    }

    // Maybe Retry
    void retry(Protos.TaskStatus status) throws IOException {
        String reason = "";
        if (status.hasMessage()) {
            reason = status.getMessage();
        }
        try {
            JobQueue.retry(status.getTaskId().getValue(), reason);
        } catch (JobNotFoundException e) {
            LOG.warn("retry({}) failed", status, e);
            // TODO: re-insert the failed job again?
        }
    }

    void finished(Protos.TaskStatus status) throws IOException {
        Optional<String> maybeUrl = Optional.empty();
        if (this.master.isPresent()) {
            maybeUrl = maybeGetUrl(status);
            LOG.info("finished: {}", maybeUrl);
        }
        int ret = status.getState().getNumber() - Protos.TaskState.TASK_FINISHED_VALUE;
        String finished = TimestampHelper.now();
        try {
            JobQueue.finished(status.getTaskId().getValue(), maybeUrl, ret, finished);
        } catch (JobNotFoundException e) {
            LOG.warn("finished({}) failed", status, e);
            // TODO: re-insert the failed job again?
        }

    }

    void failed(Protos.TaskStatus status) throws IOException {
        Optional<String> maybeUrl = Optional.empty();
        if (this.master.isPresent()) {
            maybeUrl = maybeGetUrl(status);
        }
        try {
            JobQueue.failed(status.getTaskId().getValue(), maybeUrl, status.getMessage());
        } catch (JobNotFoundException e) {
            LOG.warn("failed({}) failed", status, e);
            // TODO: re-insert the failed job again?
        }
    }

    void started(Protos.TaskStatus status) throws IOException {
        Optional<String> maybeUrl = Optional.empty();
        if (this.master.isPresent()) {
            maybeUrl = maybeGetUrl(status);
        }
        try {
            JobQueue.started(status.getTaskId().getValue(), status.getSlaveId().getValue(), maybeUrl);
        } catch (JobNotFoundException e) {
            LOG.warn("started({}) failed", status, e);
            // TODO: re-insert the failed job again?
        }
    }

    private void updateOfferStats() {
        ResourceQuantity total = new ResourceQuantity();
        for (Map.Entry<String, Protos.Offer> e : offerStock.entrySet()) {
            Resource r = ResourceConstructor.decode(e.getValue().getResourcesList());
            total.add(r.toQuantity());
        }
        total.setNodes(offerStock.size());
        StatusCache.setOfferStats(offerStock.size(), total);
    }

    // Get all running jobs and reconcile all of them - status update on database
    // will be done after statusUpdate() received. See how reconciliation must work
    // in http://mesos.apache.org/documentation/latest/reconciliation/ .
    private void reconcileAllRunningJobs(SchedulerDriver driver) throws IOException {
        List<Job> jobs = Database.getInstance().getRunning();
        List<Protos.TaskStatus> taskStatuses = jobs.stream().map(job -> {
            Protos.TaskStatus.Builder builder = Protos.TaskStatus.newBuilder()
                    .setTaskId(Protos.TaskID.newBuilder().setValue(job.taskId()))
                    // According to the document the master does not examine state but
                    // is required by protobuf to encode
                    .setState(Protos.TaskState.TASK_RUNNING);
            if (job.slaveId() != null) {
                builder.setSlaveId(Protos.SlaveID.newBuilder().setValue(job.slaveId()));
            }
            return builder.build();
        }).collect(Collectors.toList());

        if (taskStatuses.size() > 0) {
            driver.reconcileTasks(taskStatuses);
        }
    }

    private Optional<String> maybeGetUrl(Protos.TaskStatus status) {
        if (status.hasSlaveId() && status.hasExecutorId() && status.hasContainerStatus() && status.getContainerStatus().hasContainerId()) {
            return MesosHTTPFetcher.sandboxBaseUri(this.master.get(),
                    status.getSlaveId().getValue(), frameworkInfo.getId().getValue(),
                    status.getExecutorId().getValue(),
                    status.getContainerStatus().getContainerId().getValue());
        }
        // TODO: something is lacking; show log output?
        // ... should be debug because if the status piggyback is sent due to reconcilication
        // most of information is lacking in the task status
        return Optional.empty();
    }

    public boolean validateJob(Job job) {
        return maxJobSize.fits(job);
    }

    public ResourceQuantity maxJobSize() {
        return maxJobSize;
    }

    public Long maxFileSize() {
        return maxFileSize;
    }
}
