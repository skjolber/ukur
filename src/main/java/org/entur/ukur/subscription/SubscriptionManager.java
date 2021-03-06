/*
 * Licensed under the EUPL, Version 1.2 or – as soon they will be approved by
 * the European Commission - subsequent versions of the EUPL (the "Licence");
 * You may not use this work except in compliance with the Licence.
 * You may obtain a copy of the Licence at:
 *
 *  https://joinup.ec.europa.eu/software/page/eupl
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the Licence is distributed on an "AS IS" basis,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the Licence for the specific language governing permissions and
 * limitations under the Licence.
 */

package org.entur.ukur.subscription;

import com.codahale.metrics.Timer;
import org.apache.commons.lang3.SerializationUtils;
import org.apache.commons.lang3.StringUtils;
import org.entur.ukur.service.DataStorageService;
import org.entur.ukur.service.MetricsService;
import org.entur.ukur.service.QuayAndStopPlaceMappingService;
import org.entur.ukur.xml.SiriMarshaller;
import org.entur.ukur.xml.SiriObjectHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import uk.org.siri.siri20.*;

import javax.xml.datatype.Duration;
import java.io.DataOutputStream;
import java.io.Serializable;
import java.math.BigInteger;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.net.UnknownHostException;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;

import static org.entur.ukur.service.MetricsService.GAUGE_PUSH_QUEUE;
import static org.entur.ukur.subscription.SiriXMLSubscriptionHandler.SIRI_VERSION;
import static org.entur.ukur.xml.SiriObjectHelper.getBigIntegerValue;
import static org.entur.ukur.xml.SiriObjectHelper.getStringValue;

@Service
public class SubscriptionManager {

    private DataStorageService dataStorageService;
    private SiriMarshaller siriMarshaller;
    private MetricsService metricsService;
    private Map<Object, Long> alreadySentCache;
    private QuayAndStopPlaceMappingService quayAndStopPlaceMappingService;
    private String hostname;
    private Logger logger = LoggerFactory.getLogger(this.getClass());
    private ThreadPoolExecutor pushExecutor = (ThreadPoolExecutor) Executors.newFixedThreadPool(50);
    private Map<String, Long> subscriptionNextHeartbeat;
    private ZonedDateTime nextTerminatedCheck = null;

    @Autowired
    public SubscriptionManager(DataStorageService dataStorageService,
                               SiriMarshaller siriMarshaller,
                               MetricsService metricsService,
                               @Qualifier("alreadySentCache") Map<Object, Long> alreadySentCache,
                               @Qualifier("heartbeats") Map<String, Long> subscriptionNextHeartbeat,
                               QuayAndStopPlaceMappingService quayAndStopPlaceMappingService) {
        this.dataStorageService = dataStorageService;
        this.siriMarshaller = siriMarshaller;
        this.metricsService = metricsService;
        this.alreadySentCache = alreadySentCache;
        this.subscriptionNextHeartbeat = subscriptionNextHeartbeat;
        this.quayAndStopPlaceMappingService = quayAndStopPlaceMappingService;
        try {
            hostname = InetAddress.getLocalHost().getHostName();
            logger.info("This nodes hostname is '{}'", hostname);
        } catch (UnknownHostException e) {
            hostname = "random_"+new Random().nextInt(10000); //want to separate message producing nodes from each other easy in the logs, this will work as fallback
            logger.error("Cant resolve hostname - use random name '{}' instead to differentiate nodes", hostname, e);
        }
        metricsService.registerGauge(GAUGE_PUSH_QUEUE, () -> pushExecutor.getQueue().size());
        logger.info("There are at startup {} subscriptions", dataStorageService.getNumberOfSubscriptions());
    }

    public int getActivePushThreads() {
        return pushExecutor.getActiveCount();
    }

    @SuppressWarnings("unused") //Used from camel route
    public Collection<Subscription> listAll() {
        Collection<Subscription> existingSubscriptions = dataStorageService.getSubscriptions();
        logger.debug("There are {} subscriptions", existingSubscriptions.size());
        return Collections.unmodifiableCollection(existingSubscriptions);
    }

    @SuppressWarnings("unused") //Used from camel route
    public void reloadSubscriptionCache() {
        logger.info("Reloads subscription cache");
        dataStorageService.populateSubscriptionCacheFromDatastore();
    }

    public Set<Subscription> getSubscriptionsForStopPoint(String stopPointRef, SubscriptionTypeEnum type) {
        HashSet<Subscription> subscriptions = new HashSet<>();
        if (stopPointRef.startsWith("NSR:Quay:")) {
            String stopPlace = quayAndStopPlaceMappingService.mapQuayToStopPlace(stopPointRef);
            if (StringUtils.isNotBlank(stopPlace)) {
                Set<Subscription> subscriptionsForStopPlace = dataStorageService.getSubscriptionsForStopPoint(stopPlace, type);
                logger.trace("Found {} subscriptions for stopPlace {} which quay {} is part of", subscriptionsForStopPlace.size(), stopPlace, stopPointRef);
                subscriptions.addAll(subscriptionsForStopPlace);
            }
        }
        Set<Subscription> subscriptionsForStopPoint = dataStorageService.getSubscriptionsForStopPoint(stopPointRef, type);
        logger.trace("Found {} subscriptions for {}", subscriptionsForStopPoint.size(), stopPointRef);
        subscriptions.addAll(subscriptionsForStopPoint);
        return subscriptions;
    }

    public Set<Subscription> getSubscriptionsForLineRef(String lineRef, SubscriptionTypeEnum type) {
        return dataStorageService.getSubscriptionsForLineRefAndNoStops(lineRef, type);
    }

    public Set<Subscription> getSubscriptionsForCodespace(String codespace, SubscriptionTypeEnum type) {
        return dataStorageService.getSubscriptionsForCodespaceAndNoStops(codespace, type);
    }

    public void notifySubscriptionsOnStops(HashSet<Subscription> subscriptions, EstimatedVehicleJourney estimatedVehicleJourney) {
        for (Subscription subscription : subscriptions) {
            Set<String> subscribedStops = getAllStops(subscription);
            EstimatedVehicleJourney clone = clone(estimatedVehicleJourney);
            //Removes all other estimated calls than those subscribed upon:
            if (clone.getEstimatedCalls() != null && clone.getEstimatedCalls().getEstimatedCalls() != null) {
                Iterator<EstimatedCall> iterator = clone.getEstimatedCalls().getEstimatedCalls().iterator();
                while (iterator.hasNext()) {
                    EstimatedCall call = iterator.next();
                    String ref = getStringValue(call.getStopPointRef());
                    if (!subscribedStops.contains(ref)) {
                        iterator.remove();
                    }
                }
            }
            //Removes all other recorded calls than those subscribed upon:
            if (clone.getRecordedCalls() != null && clone.getRecordedCalls().getRecordedCalls() != null) {
                Iterator<RecordedCall> iterator = clone.getRecordedCalls().getRecordedCalls().iterator();
                while (iterator.hasNext()) {
                    RecordedCall call = iterator.next();
                    String ref = getStringValue(call.getStopPointRef());
                    if (!subscribedStops.contains(ref)) {
                        iterator.remove();
                    }
                }
            }
            clone.setIsCompleteStopSequence(false); //since we have tampered with the calls!
            pushMessage(subscription, clone);
        }
    }

    public void notifySubscriptionsWithFullMessage(HashSet<Subscription> subscriptions, EstimatedVehicleJourney estimatedVehicleJourney) {
        for (Subscription subscription : subscriptions) {
            pushMessage(subscription, estimatedVehicleJourney);
        }
    }

    public void notifySubscriptions(HashSet<Subscription> subscriptions, PtSituationElement ptSituationElement) {
        for (Subscription subscription : subscriptions) {
            Set<String> subscribedStops = getAllStops(subscription);
            PtSituationElement clone = clone(ptSituationElement);
            AffectsScopeStructure affects = clone.getAffects();
            if (affects != null) {
                //clears elements not covered by the norwegian profile to reduce size on push-message:
                affects.setAreaOfInterest(null);
                affects.setExtensions(null);
                affects.setOperators(null);
                affects.setPlaces(null);
                affects.setStopPoints(null);
                affects.setRoads(null);
                affects.setVehicles(null);
                //removes part not subscribed upon (to reduce size on push-message):
                if (subscription.getCodespaces().isEmpty() || !subscription.hasNoStops() || !subscription.getLineRefs().isEmpty()) {
                    removeUnsubscribedNetworks(subscription, affects.getNetworks());
                    if (subscription.hasNoStops()) {
                        affects.setStopPlaces(null);
                    } else {
                        removeUnsubscribedStopPlaces(subscribedStops, affects.getStopPlaces());
                    }
                    removeUnsubscribedJourneys(subscription, subscribedStops, affects.getVehicleJourneys());
                }
            }

            if (withAffects(clone)) {
                pushMessage(subscription, clone);
            } else {
                BigInteger version = SiriObjectHelper.getBigIntegerValue(clone.getVersion());
                String situationNumber = getStringValue(clone.getSituationNumber());
                logger.info("do not push PtSituationElement with situationnumber {} and version {} to subscription with id {} as all affects are removed", situationNumber, version, subscription.getId());
            }
        }
    }

    Subscription getSubscriptionByName(String name) {
        return dataStorageService.getSubscriptionByName(name);
    }

    private void removeUnsubscribedJourneys(Subscription subscription, Set<String> subscribedStops, AffectsScopeStructure.VehicleJourneys vehicleJourneys) {
        //Removes affected VehicleJourneys (and unsubscribed stops) without any stops, lines or vehicles subscribed upon
        if (vehicleJourneys != null && vehicleJourneys.getAffectedVehicleJourneies() != null) {
            Iterator<AffectedVehicleJourneyStructure> iterator = vehicleJourneys.getAffectedVehicleJourneies().iterator();
            while (iterator.hasNext()) {
                AffectedVehicleJourneyStructure journeyStructure = iterator.next();
                boolean removeJourney = true;
                if (isSubscribed(subscription, journeyStructure) && journeyStructure.getRoutes() != null) {
                    List<AffectedRouteStructure> routes = journeyStructure.getRoutes();
                    if (!subscription.hasNoStops()) {
                        Iterator<AffectedRouteStructure> routeStructureIterator = routes.iterator();
                        while (routeStructureIterator.hasNext()) {
                            AffectedRouteStructure routeStructure = routeStructureIterator.next();
                            AffectedRouteStructure.StopPoints stopPoints = routeStructure.getStopPoints();
                            if (stopPoints != null && stopPoints.getAffectedStopPointsAndLinkProjectionToNextStopPoints() != null) {
                                List<Serializable> affectedStopPointsAndLinkProjectionToNextStopPoints = stopPoints.getAffectedStopPointsAndLinkProjectionToNextStopPoints();
                                Iterator<Serializable> stops = affectedStopPointsAndLinkProjectionToNextStopPoints.iterator();
                                while (stops.hasNext()) {
                                    Serializable stop = stops.next();
                                    if (stop instanceof AffectedStopPointStructure) {
                                        AffectedStopPointStructure affectedStopPoint = (AffectedStopPointStructure) stop;
                                        String ref = getStringValue(affectedStopPoint.getStopPointRef());
                                        if (!subscribedStops.contains(ref)) {
                                            stops.remove();
                                        }
                                    }
                                }
                                if (affectedStopPointsAndLinkProjectionToNextStopPoints.isEmpty()) {
                                    routeStructureIterator.remove();
                                }
                            }
                        }
                    }
                    removeJourney = routes.isEmpty();
                }
                if (removeJourney) {
                    iterator.remove();
                }
            }
        }
    }

    private void removeUnsubscribedStopPlaces(Set<String> subscribedStops, AffectsScopeStructure.StopPlaces stopPlaces) {
        if (stopPlaces != null && stopPlaces.getAffectedStopPlaces() != null) {
            Iterator<AffectedStopPlaceStructure> iterator = stopPlaces.getAffectedStopPlaces().iterator();
            while (iterator.hasNext()) {
                AffectedStopPlaceStructure stop = iterator.next();
                String ref = getStringValue(stop.getStopPlaceRef());
                if (!subscribedStops.contains(ref)) {
                    iterator.remove();
                }
            }
        }
    }

    private void removeUnsubscribedNetworks(Subscription subscription, AffectsScopeStructure.Networks networks) {
        if (networks != null && networks.getAffectedNetworks() != null) {
            Iterator<AffectsScopeStructure.Networks.AffectedNetwork> networkIterator = networks.getAffectedNetworks().iterator();
            while (networkIterator.hasNext()) {
                AffectsScopeStructure.Networks.AffectedNetwork affectedNetwork = networkIterator.next();
                boolean keep = false;
                List<AffectedLineStructure> affectedLines = affectedNetwork.getAffectedLines();
                if (affectedLines != null) {
                    Iterator<AffectedLineStructure> lineIterator = affectedLines.iterator();
                    while (lineIterator.hasNext()) {
                        AffectedLineStructure line = lineIterator.next();
                        if (subscription.getLineRefs().contains(getStringValue(line.getLineRef()))) {
                            keep = true;
                        } else {
                            lineIterator.remove();
                        }
                    }
                }
                if (!keep) {
                    networkIterator.remove();
                }
            }
        }
    }

    private boolean withAffects(PtSituationElement element) {
        AffectsScopeStructure affects = element.getAffects();
        if (affects == null) {
            return false;
        }
        List<AffectedStopPlaceStructure> stopPlaces = affects.getStopPlaces() == null ? null : affects.getStopPlaces().getAffectedStopPlaces();
        List<AffectedVehicleJourneyStructure> vehicleJourneys = affects.getVehicleJourneys() == null ? null : affects.getVehicleJourneys().getAffectedVehicleJourneies();
        List<AffectsScopeStructure.Networks.AffectedNetwork> networks = affects.getNetworks() == null ? null : affects.getNetworks().getAffectedNetworks();
        boolean hasStopPlace = stopPlaces != null && !stopPlaces.isEmpty();
        boolean hasVehicleJourney = vehicleJourneys != null && !vehicleJourneys.isEmpty();
        boolean hasNetworks = networks != null && !networks.isEmpty();
        return hasStopPlace || hasVehicleJourney || hasNetworks;
    }

    private boolean isSubscribed(Subscription subscription, AffectedVehicleJourneyStructure journey) {
        if (!subscription.getLineRefs().isEmpty() && journey.getLineRef() != null) {
            String lineref = journey.getLineRef().getValue();
            return !StringUtils.isNotBlank(lineref) || subscription.getLineRefs().contains(lineref);
        }
        return true;
    }

    @SuppressWarnings({"unused", "UnusedReturnValue"}) //Used from Camel REST api
    public Subscription addOrUpdate(Subscription s) {
        return addOrUpdate(s, false);
    }

    @SuppressWarnings({"unused"}) //Used from Camel quartz trigger route
    public void handleHeartbeatAndTermination() {
        handleHeartbeatAndTermination(ZonedDateTime.now());
    }

    void handleHeartbeatAndTermination(ZonedDateTime now) {

        Date dateNow = Date.from(now.toInstant());
        long epochNow = dateNow.getTime();

        Collection<Subscription> subscriptions = new HashSet<>(dataStorageService.getSubscriptions());

        //check for terminated subscriptions only every 3 hour (node local timestamp as there is no harm if we check more frequent)
        if (nextTerminatedCheck == null || nextTerminatedCheck.isAfter(now)) {
            nextTerminatedCheck = now.plusHours(3);
            for (Iterator<Subscription> iterator = subscriptions.iterator(); iterator.hasNext(); ) {
                Subscription subscription = iterator.next();
                if (subscription.getInitialTerminationTime() != null && now.isAfter(subscription.getInitialTerminationTime())) {
                    logger.info("Removes subscription with InitialTerminationTime in the past - subscription id={}, name={}", subscription.getId(), subscription.getName());
                    pushNotification(subscription, NotificationTypeEnum.subscriptionTerminated);
                    remove(subscription.getId());
                    iterator.remove(); //to prevent from also sending heartbeat
                }
            }
        }

        for (Subscription subscription : subscriptions) {
            Duration heartbeatInterval = subscription.getHeartbeatInterval();
            if (heartbeatInterval != null) {
                Long nextHeartbeat = subscriptionNextHeartbeat.get(subscription.getId());
                if (nextHeartbeat == null || nextHeartbeat < epochNow) {
                    long epochNextNotification = heartbeatInterval.getTimeInMillis(dateNow) + epochNow;
                    subscriptionNextHeartbeat.put(subscription.getId(), epochNextNotification);
                    if (nextHeartbeat != null) {
                        pushNotification(subscription, NotificationTypeEnum.heartbeat);
                    } //else we assume subscription is just created - and don't notify until next time
                }
            }
        }
    }

    Subscription addOrUpdate(Subscription s, boolean siriXML) {
        if (s == null) {
            throw new IllegalArgumentException("No subscription given");
        }
        if (StringUtils.isBlank(s.getPushAddress())) {
            throw new IllegalArgumentException("PushAddress is required");
        }
        s.normalizeAndRemoveIgnoredStops();
        boolean noToStops = s.getToStopPoints().isEmpty();
        boolean noFromStops = s.getFromStopPoints().isEmpty();
        boolean noCodespaces = s.getCodespaces().isEmpty();
        boolean noLineRefs = s.getLineRefs().isEmpty();
        if (noToStops && noFromStops && noCodespaces && noLineRefs) {
            throw new IllegalArgumentException("No criterias given, must have at least one lineRef, one codespace or a fromStop and a toStop");
        }
        if ( (noFromStops && !noToStops) || (noToStops && !noFromStops)) {
            throw new IllegalArgumentException("Must have both TO and FROM stops");
        }
        if (!siriXML && s.isSiriXMLBasedSubscription()) {
            throw new IllegalArgumentException("Illegal name (can't start with 'SIRI-XML')");
        }
        if (StringUtils.isNotBlank(s.getId())) {
            logger.info("Attempts to updates subscription with id {}", s.getId());
            if ( dataStorageService.updateSubscription(s)) {
                logger.info("Updated subscription with id {} successfully", s.getId());
            } else {
                throw new IllegalArgumentException("Could not update subscription");
            }
            return s;
        } else {
            Subscription added = dataStorageService.addSubscription(s);
            logger.info("Added new subscription - assigns id: {}", added.getId());
            return added;
        }
    }

    @SuppressWarnings({"unused", "UnusedReturnValue", "WeakerAccess"}) //Used from Camel REST api
    public void remove(String subscriptionId) {
        logger.info("Removes subscription with id {}", subscriptionId);
        dataStorageService.removeSubscription(subscriptionId);
        subscriptionNextHeartbeat.remove(subscriptionId);
    }

    private Set<String> getAllStops(Subscription subscription) {
        Set<String> fromStopPoints = subscription.getFromStopPoints();
        Set<String> toStopPoints = subscription.getToStopPoints();
        HashSet<String> result = new HashSet<>();
        if (fromStopPoints != null) {
            result.addAll(fromStopPoints);
        }
        if (toStopPoints != null) {
            result.addAll(toStopPoints);
        }
        HashSet<String> mappedQuays = new HashSet<>();
        for (String stopPoint : result) {
            if (stopPoint.startsWith("NSR:StopPlace:")) {
                mappedQuays.addAll(quayAndStopPlaceMappingService.mapStopPlaceToQuays(stopPoint));
            }
        }
        result.addAll(mappedQuays);
        return result;
    }

    private <T extends Serializable> T clone(T toClone) {
        return SerializationUtils.clone(toClone);
    }

    private void pushMessage(Subscription subscription, Object siriElement) {

        String alreadySentKey = calculateUniqueKey(subscription, siriElement);
        Long ifPresent = alreadySentCache.get(alreadySentKey);
        //TODO: ROR-282 (Støtte endret validity for SX meldinger)

        if (ifPresent != null) {
            long diffInSecs = (System.currentTimeMillis() - ifPresent) / 1000;
            logger.debug("skips message since it has already been pushed to the same subscription (id={}) {} seconds ago", subscription.getId(), diffInSecs);
            return;
        }

        alreadySentCache.put(alreadySentKey, System.currentTimeMillis());
        logger.debug("PUSH ({}) {} to subscription with id={}, name={}, pushAddress={}", hostname, siriElement.getClass().getSimpleName(), subscription.getId(), subscription.getName(), subscription.getPushAddress());
        pushToHttp(subscription, siriElement);
    }

    private String calculateUniqueKey(Subscription subscription, Object siriElement) {
        if (siriElement instanceof PtSituationElement) {
            PtSituationElement situationElement = (PtSituationElement) siriElement;
            String sitNumber = getStringValue(situationElement.getSituationNumber());
            String situationNumber = sitNumber == null ? UUID.randomUUID().toString() : sitNumber;
            BigInteger version = getBigIntegerValue(situationElement.getVersion());
            return subscription.getId()+"_"+situationNumber+"_"+version;
        }
        String content;
        try {
            //Can't use the cxf generated objects directly, has to convert it to something we can compare
            content = siriMarshaller.marshall(siriElement);
        } catch (Exception e) {
            logger.warn("Could not marshall {}", e);
            content = subscription.getId()+"_"+UUID.randomUUID().toString();
        }
        return subscription.getId()+"_"+content.length()+"_"+content.hashCode();
    }

    private void pushNotification(Subscription subscription, NotificationTypeEnum type) {
        pushExecutor.execute(() -> {
            try {
                Siri siri = new Siri();
                siri.setVersion(SIRI_VERSION);
                switch (type) {
                    case heartbeat:
                        HeartbeatNotificationStructure heartbeatNotification = new HeartbeatNotificationStructure();
                        siri.setHeartbeatNotification(heartbeatNotification);
                        heartbeatNotification.setRequestTimestamp(ZonedDateTime.now());
                        RequestorRef producerRef = new RequestorRef();
                        producerRef.setValue(subscription.getName());
                        heartbeatNotification.setProducerRef(producerRef);
                        break;
                    case subscriptionTerminated:
                        SubscriptionTerminatedNotificationStructure subscriptionTerminatedNotification = new SubscriptionTerminatedNotificationStructure();
                        subscriptionTerminatedNotification.setResponseTimestamp(ZonedDateTime.now());
                        RequestorRef requestorRef = new RequestorRef();
                        requestorRef.setValue(subscription.getSiriRequestor());
                        subscriptionTerminatedNotification.getSubscriberRevesAndSubscriptionRevesAndSubscriptionFilterReves().add(requestorRef);
                        SubscriptionQualifierStructure subscriptionQualifierStructure = new SubscriptionQualifierStructure();
                        subscriptionQualifierStructure.setValue(subscription.getSiriClientGeneratedId());
                        subscriptionTerminatedNotification.getSubscriberRevesAndSubscriptionRevesAndSubscriptionFilterReves().add(subscriptionQualifierStructure);
                        siri.setSubscriptionTerminatedNotification(subscriptionTerminatedNotification);
                        break;
                    default:
                        logger.error("Called without proper type specified...");
                        return;
                }
                HttpStatus httpStatus = post(subscription, subscription.getPushAddress(), siri);
                logger.info("Posted a {} notification for subscription with id={}, {} responded {}", type, subscription.getId(), subscription.getPushAddress(), httpStatus);
            } catch (Exception e) {
                logger.error("Got exception while pushing message", e);
            }
        });
    }

    private void pushToHttp(Subscription subscription, Object siriElement) {
        pushExecutor.execute(() -> {
            try {
                String pushAddress = subscription.getPushAddress();
                final Object pushMessage;
                if (subscription.isUseSiriSubscriptionModel()) {
                    Siri siri = new Siri();
                    siri.setVersion(SIRI_VERSION);
                    siri.setServiceDelivery(new ServiceDelivery());
                    siri.getServiceDelivery().setResponseTimestamp(ZonedDateTime.now()); //TODO: Should get this from the original message - now (if it is used) is probably wrong...
                    RequestorRef producer = new RequestorRef();
                    if (siriElement instanceof EstimatedVehicleJourney) {
                        producer.setValue(((EstimatedVehicleJourney) siriElement).getDataSource());
                        EstimatedTimetableDeliveryStructure estimatedTimetableDeliveryStructure = new EstimatedTimetableDeliveryStructure();
                        EstimatedVersionFrameStructure estimatedVersionFrameStructure = new EstimatedVersionFrameStructure();
                        estimatedVersionFrameStructure.getEstimatedVehicleJourneies().add((EstimatedVehicleJourney) siriElement);
                        estimatedTimetableDeliveryStructure.getEstimatedJourneyVersionFrames().add(estimatedVersionFrameStructure);
                        siri.getServiceDelivery().getEstimatedTimetableDeliveries().add(estimatedTimetableDeliveryStructure);
                    } else if (siriElement instanceof PtSituationElement) {
                        producer.setValue(((PtSituationElement) siriElement).getParticipantRef().getValue());
                        SituationExchangeDeliveryStructure situationExchangeDeliveryStructure = new SituationExchangeDeliveryStructure();
                        SituationExchangeDeliveryStructure.Situations situations = new SituationExchangeDeliveryStructure.Situations();
                        situationExchangeDeliveryStructure.setSituations(situations);
                        situations.getPtSituationElements().add((PtSituationElement) siriElement);
                        siri.getServiceDelivery().getSituationExchangeDeliveries().add(situationExchangeDeliveryStructure);
                    }
                    siri.getServiceDelivery().setProducerRef(producer);
                    pushMessage = siri;
                } else {
                    //TODO: Remove this else-block once transition to the siri subscription model is completed! And subscription.useSiriSubscriptionModel() as well
                    pushMessage = siriElement;
                    if (siriElement instanceof EstimatedVehicleJourney) {
                        pushAddress += "/et";
                    } else if (siriElement instanceof PtSituationElement) {
                        pushAddress += "/sx";
                    }
                }
                HttpStatus responseStatus = post(subscription, pushAddress, pushMessage);
                if (HttpStatus.RESET_CONTENT.equals(responseStatus)) {
                    logger.info("Receive {} on push to {} and removes subscription with id {}", HttpStatus.RESET_CONTENT, pushAddress, subscription.getId());
                    remove(subscription.getId());
                } else if (HttpStatus.OK.equals(responseStatus)) {
                    if (subscription.getFailedPushCounter() > 0) {
                        subscription.resetFailedPushCounter();
                        dataStorageService.updateSubscription(subscription);
                    }
                } else {
                    logger.info("Unexpected response code on push '{}' - increase failed push counter for subscription wih id {}", responseStatus, subscription.getId());
                    long failedPushCounter = subscription.increaseFailedPushCounter();
                    if (failedPushCounter > 3) {
                        logger.info("Removes subscription with id {} after {} failed push attempts", subscription.getId(), failedPushCounter);
                        remove(subscription.getId());
                    } else {
                        dataStorageService.updateSubscription(subscription);
                    }
                }
            } catch (Exception e) {
                logger.error("Got exception while pushing message", e);
            }
        });
    }

    private HttpStatus post(Subscription subscription, String pushAddress, Object pushMessage) {
        Timer pushToHttp = metricsService.getTimer(MetricsService.TIMER_PUSH);
        Timer.Context context = pushToHttp.time();
        try {
            String payload = siriMarshaller.marshall(pushMessage);
            byte[] bytes = payload.getBytes();
            URL url = new URL(pushAddress);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/xml");
            connection.setRequestProperty("Content-Length", "" + Integer.toString(bytes.length));
            connection.setDoOutput(true);
            DataOutputStream out = new DataOutputStream(connection.getOutputStream());
            out.write(bytes);
            out.flush();
            out.close();
            int responseCode = connection.getResponseCode();
            logger.trace("Receive {} on push to {} for subscription with id {}", responseCode, subscription.getPushAddress(), subscription.getId());
            return HttpStatus.valueOf(responseCode);
        } catch (Exception e) {
            logger.warn("Could not push to {} for subscription with id {}", subscription.getPushAddress(), subscription.getId(), e);
            return null;
        } finally {
            context.stop();
        }
    }

    private enum NotificationTypeEnum {
        heartbeat,
        subscriptionTerminated
    }
}
