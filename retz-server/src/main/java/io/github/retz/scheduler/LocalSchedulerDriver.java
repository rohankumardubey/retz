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

import com.google.protobuf.InvalidProtocolBufferException;
import org.apache.mesos.Protos;
import org.apache.mesos.Scheduler;
import org.apache.mesos.SchedulerDriver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

public class LocalSchedulerDriver implements SchedulerDriver {
    private static final Logger LOG = LoggerFactory.getLogger(LocalSchedulerDriver.class);

    Scheduler scheduler;
    Protos.FrameworkInfo frameworkInfo;
    Protos.MasterInfo masterInfo;

    private final List<Protos.OfferID> declined;

    private final List<Protos.OfferID> accepted;
    private final List<Protos.TaskInfo> tasks;

//    List<Protos.Resource> reserved;
//    List<Protos.Resource> volumes;
//
//    Map<Protos.OfferID, Protos.Offer> offers;

    private final AtomicBoolean running = new AtomicBoolean(true);

    LocalSchedulerDriver(Scheduler scheduler,
                         Protos.FrameworkInfo frameworkInfo,
                         String mesosMaster)
            throws InvalidProtocolBufferException {
        LOG.info("{} starting", this.getClass().getName());
        LOG.warn("Currently this is just a mock to test HTTP server and clients. No jobs will be executed.");
        /*
        assertNotNull(scheduler);
        assertNotNull(frameworkInfo);
        assertNotNull(frameworkInfo.getId());
        assertNotNull(frameworkInfo.getName());
        assertNotNull(mesosMaster);
        */
        this.scheduler = scheduler;
        Protos.FrameworkID frameworkID = Protos.FrameworkID.newBuilder()
                .setValue(UUID.randomUUID().toString())
                .build();
        this.frameworkInfo = frameworkInfo.toBuilder()
                .setId(frameworkID)
                .build();
        System.err.println(mesosMaster);

        String[] master = mesosMaster.split(":");

        this.masterInfo = Protos.MasterInfo.newBuilder()
                .setHostname(master[0])
                .setPort(Integer.parseInt(master[1]))
                .setId("mesosMaster")
                .setIp(10)
                .build();
        declined = new ArrayList<>();
        accepted = new ArrayList<>();
        tasks = new ArrayList<>();
    }

    public Protos.Status start() {
        scheduler.registered(this, frameworkInfo.getId(), masterInfo);
        running.set(true);
        return Protos.Status.DRIVER_RUNNING;
    }

    public Protos.Status stop() {
        scheduler.disconnected(this);
        running.lazySet(false);
        return Protos.Status.DRIVER_STOPPED;
    }

    public Protos.Status stop(boolean b) {
        scheduler.disconnected(this);
        scheduler.reregistered(this, masterInfo);
        running.lazySet(false);
        return Protos.Status.DRIVER_STOPPED;
    }

    public Protos.Status abort() {
        scheduler.disconnected(this);
        running.lazySet(false);
        return Protos.Status.DRIVER_ABORTED;
    }

    public Protos.Status join() {
        while (running.get()) {
            try {
                Thread.sleep(1024);
            } catch (InterruptedException e) {
            }
        }
        return Protos.Status.DRIVER_STOPPED;
    }

    public Protos.Status run() {
        start();
        stop();
        return join();
    }

    public Protos.Status requestResources(Collection<Protos.Request> requests) {
        //scheduler.resourceOffers();
        return Protos.Status.DRIVER_RUNNING;
    }

    @Deprecated
    public Protos.Status launchTasks(Collection<Protos.OfferID> offerIds,
                                     Collection<Protos.TaskInfo> tasksToLaunch,
                                     Protos.Filters filters) {
        // Shouldn't be used
        throw new UnknownError();
    }

    @Deprecated
    public Protos.Status launchTasks(Collection<Protos.OfferID> offerIds,
                                     Collection<Protos.TaskInfo> taskToLaunch) {
        // Shouldn't be used
        throw new UnknownError();
    }

    @Deprecated
    public Protos.Status launchTasks(Protos.OfferID offerId,
                                     Collection<Protos.TaskInfo> taskToLaunch) {
        // Shouldn't be used
        throw new UnknownError();
    }

    @Deprecated
    public Protos.Status launchTasks(Protos.OfferID offerId,
                                     Collection<Protos.TaskInfo> tasksToLaunch,
                                     Protos.Filters filters) {
        // Shouldn't be used
        throw new UnknownError();
    }

    public Protos.Status killTask(Protos.TaskID taskId) {
        return Protos.Status.DRIVER_RUNNING;
    }

    public Protos.Status acceptOffers(Collection<Protos.OfferID> offerIds,
                                      Collection<Protos.Offer.Operation> operations,
                                      Protos.Filters filters) {
        // TODO: this function must be implemented to test result fetching (do we support Docker as well?)
        // TODO: wonder NotImplementedException is suitable as this is originally for refleciton.
        throw new RuntimeException("Not implemented yet");
//        this.accepted.addAll(offerIds);
//        for (Protos.OfferID id : offerIds) {
//            Protos.Offer.Builder ob = offers.get(id).toBuilder();
//
//
//            for (Protos.Offer.Operation op : operations) {
//                switch (op.getType().getNumber()) {
//                    case Protos.Offer.Operation.Type.LAUNCH_VALUE: {
//                        tasks.addAll(op.getLaunch().getTaskInfosList());
//                        break;
//                    }
//                    case Protos.Offer.Operation.Type.RESERVE_VALUE: {
//                        // TODO: aggregate against same principal/roles
//                        reserved.addAll(op.getReserve().getResourcesList());
//                        break;
//                    }
//                    case Protos.Offer.Operation.Type.CREATE_VALUE: {
//                        volumes.addAll(op.getCreate().getVolumesList());
//                        break;
//                    }
//                    case Protos.Offer.Operation.Type.DESTROY_VALUE: {
//                        for (Protos.Resource r : op.getDestroy().getVolumesList()) {
//                            volumes.remove(r);
//                        }
//                        break;
//                    }
//                    case Protos.Offer.Operation.Type.UNRESERVE_VALUE: {
//                        for (Protos.Resource r : op.getUnreserve().getResourcesList()) {
//                            reserved.remove(r);
//                        }
//                        break;
//                    }
//                    default:
//                        fail();
//                }
//            }
//        }
//        return Protos.Status.DRIVER_RUNNING;
    }

    public Protos.Status declineOffer(Protos.OfferID offerID, Protos.Filters filters) {
        declined.add(offerID);
        return Protos.Status.DRIVER_RUNNING;
    }

    public Protos.Status declineOffer(Protos.OfferID offerID) {
        declined.add(offerID);
        return Protos.Status.DRIVER_RUNNING;
    }

    public Protos.Status reviveOffers() {
        return Protos.Status.DRIVER_RUNNING;
    }

    public Protos.Status suppressOffers() {
        return Protos.Status.DRIVER_RUNNING;
    }

    public Protos.Status acknowledgeStatusUpdate(Protos.TaskStatus status) {
        return Protos.Status.DRIVER_RUNNING;
    }

    public Protos.Status sendFrameworkMessage(Protos.ExecutorID executorID,
                                              Protos.SlaveID slaveID,
                                              byte[] data) {
        return Protos.Status.DRIVER_RUNNING;
    }

    public Protos.Status reconcileTasks(Collection<Protos.TaskStatus> statuses) {
        return Protos.Status.DRIVER_RUNNING;
    }

    public void dummyOffer(List<Protos.Offer> offers) {
        scheduler.resourceOffers(this, offers);
    }

    public Protos.FrameworkInfo getFrameworkInfo() {
        return frameworkInfo;
    }

    public List<Protos.OfferID> getDeclined() {
        return declined;
    }

    public List<Protos.OfferID> getAccepted() {
        return accepted;
    }

    public List<Protos.TaskInfo> getTasks() {
        return tasks;
    }

    public void clear() {
        declined.clear();
        accepted.clear();
        tasks.clear();
    }

    public void dummyTaskStarted() {
        dummyTaskUpdate(Protos.TaskState.TASK_STARTING);
    }

    public void dummyTaskFinish() {
        dummyTaskUpdate(Protos.TaskState.TASK_FINISHED);
    }

    private void dummyTaskUpdate(Protos.TaskState state) {
        for (Protos.TaskInfo task : tasks) {
            scheduler.statusUpdate(this,
                    Protos.TaskStatus.newBuilder()
                            .setExecutorId(task.getExecutor().getExecutorId())
                            .setState(state)
                            .setTaskId(task.getTaskId())
                            .setSlaveId(task.getSlaveId())
                            .build());
        }
    }
}
