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

import com.github.tomakehurst.wiremock.junit.WireMockRule;
import com.github.tomakehurst.wiremock.matching.RequestPatternBuilder;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.core.IMap;
import com.hazelcast.core.ITopic;
import com.hazelcast.test.TestHazelcastInstanceFactory;
import org.entur.ukur.routedata.LiveJourney;
import org.entur.ukur.service.DataStorageService;
import org.entur.ukur.service.MetricsService;
import org.entur.ukur.service.QuayAndStopPlaceMappingService;
import org.entur.ukur.testsupport.DatastoreTest;
import org.entur.ukur.xml.SiriMarshaller;
import org.hamcrest.CoreMatchers;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import uk.org.siri.siri20.*;

import javax.xml.bind.JAXBException;
import javax.xml.datatype.DatatypeFactory;
import javax.xml.datatype.Duration;
import javax.xml.stream.XMLStreamException;
import java.math.BigInteger;
import java.time.ZonedDateTime;
import java.util.*;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.hamcrest.CoreMatchers.hasItem;
import static org.junit.Assert.*;
import static org.mockito.Mockito.mock;

public class SubscriptionManagerWiremockTest extends DatastoreTest {

    @Rule
    public WireMockRule wireMockRule = new WireMockRule(options().dynamicPort());

    private SubscriptionManager subscriptionManager;
    private Map<Object, Long> alreadySentCache;
    private SiriMarshaller siriMarshaller;
    private DataStorageService dataStorageService;

    @SuppressWarnings("unchecked")
    @Before
    public void setUp() throws Exception {
        super.setUp();
        alreadySentCache =  new HashMap<>();
        siriMarshaller = new SiriMarshaller();
        HazelcastInstance hazelcastInstance = new TestHazelcastInstanceFactory().newHazelcastInstance();
        IMap<String, LiveJourney> liveJourneyIMap = hazelcastInstance.getMap("journeys");
        ITopic<String> subscriptionTopic = hazelcastInstance.getTopic("subscriptions");
        liveJourneyIMap.clear();
        MetricsService metricsService = new MetricsService();
        dataStorageService = new DataStorageService(datastore, liveJourneyIMap, subscriptionTopic);
        subscriptionManager = new SubscriptionManager(dataStorageService, siriMarshaller, metricsService, alreadySentCache, new HashMap<>(), mock(QuayAndStopPlaceMappingService.class));
    }

    @Test
    public void testETPushOk()  {

        String url = "/push/ok/et";
        stubFor(post(urlEqualTo(url))
                .withHeader("Content-Type", equalTo("application/xml"))
                .willReturn(aResponse()));

        Subscription subscription = createSubscription(url, "NSR:Quay:232", "NSR:Quay:125", null);
        verify(0, postRequestedFor(urlEqualTo(url)));
        assertEquals(0, subscription.getFailedPushCounter());
        HashSet<Subscription> subscriptions = new HashSet<>();
        subscriptions.add(subscription);
        subscriptionManager.notifySubscriptionsOnStops(subscriptions, new EstimatedVehicleJourney());
        waitAndVerifyAtLeast(1, postRequestedFor(urlEqualTo(url)));
        assertEquals(0, subscription.getFailedPushCounter());
    }

    @Test
    public void testETPushOkWithSiriRoot() throws JAXBException, XMLStreamException {

        String url = "/push/ok-siri/";
        stubFor(post(urlEqualTo(url))
                .withHeader("Content-Type", equalTo("application/xml"))
                .willReturn(aResponse()));

        Subscription subscription = new Subscription();
        subscription.addFromStopPoint("NSR:Quay:232");
        subscription.addToStopPoint("NSR:Quay:125");
        subscription.setName("Push over http test");
        subscription.setPushAddress("http://localhost:" + wireMockRule.port() + url);
        subscription.setUseSiriSubscriptionModel(true);
        subscription = subscriptionManager.addOrUpdate(subscription);

        verify(0, postRequestedFor(urlEqualTo(url)));
        HashSet<Subscription> subscriptions = new HashSet<>();
        subscriptions.add(subscription);
        EstimatedVehicleJourney et = new EstimatedVehicleJourney();
        et.setDataSource("TEST");
        subscriptionManager.notifySubscriptionsOnStops(subscriptions, et);
        waitAndVerifyAtLeast(1, postRequestedFor(urlEqualTo(url)));
        List<LoggedRequest> loggedRequests = findAll(postRequestedFor(urlEqualTo(url)));
        assertEquals(1, loggedRequests.size());
        LoggedRequest loggedRequest = loggedRequests.get(0);
        String xml = new String(loggedRequest.getBody());
        Siri siri = siriMarshaller.unmarshall(xml, Siri.class);
        assertNotNull(siri);
        assertNotNull(siri.getServiceDelivery());
        assertNotNull(siri.getServiceDelivery().getResponseTimestamp());
        assertNotNull(siri.getServiceDelivery().getProducerRef());
        assertEquals("TEST", siri.getServiceDelivery().getProducerRef().getValue());
        assertNotNull(siri.getServiceDelivery().getEstimatedTimetableDeliveries());
        assertEquals(1, siri.getServiceDelivery().getEstimatedTimetableDeliveries().size());
        EstimatedTimetableDeliveryStructure estimatedTimetableDeliveryStructure = siri.getServiceDelivery().getEstimatedTimetableDeliveries().get(0);
        assertNotNull(estimatedTimetableDeliveryStructure);
        List<EstimatedVersionFrameStructure> estimatedJourneyVersionFrames = estimatedTimetableDeliveryStructure.getEstimatedJourneyVersionFrames();
        assertNotNull(estimatedJourneyVersionFrames);
        assertEquals(1, estimatedJourneyVersionFrames.size());
        assertNotNull(estimatedJourneyVersionFrames.get(0));
        assertNotNull(estimatedJourneyVersionFrames.get(0).getEstimatedVehicleJourneies());
        assertEquals(1, estimatedJourneyVersionFrames.get(0).getEstimatedVehicleJourneies().size());
        assertNotNull(estimatedJourneyVersionFrames.get(0).getEstimatedVehicleJourneies().get(0));
        assertEquals("TEST", estimatedJourneyVersionFrames.get(0).getEstimatedVehicleJourneies().get(0).getDataSource());
    }


    @Test
    public void testETPushForget()  {

        String url = "/push/forget/et";
        stubFor(post(urlEqualTo(url))
                .withHeader("Content-Type", equalTo("application/xml"))
                .willReturn(aResponse().withStatus(205)));

        Subscription subscription = createSubscription(url, "NSR:Quay:232", "NSR:Quay:125", null);
        verify(0, postRequestedFor(urlEqualTo(url)));
        assertEquals(0, subscription.getFailedPushCounter());
        HashSet<Subscription> subscriptions = new HashSet<>();
        subscriptions.add(subscription);
        assertThat(dataStorageService.getSubscriptions(), hasItem(subscription));
        subscriptionManager.notifySubscriptionsOnStops(subscriptions, new EstimatedVehicleJourney());
        waitAndVerifyAtLeast(1, postRequestedFor(urlEqualTo(url)));
        waitUntilSubscriptionIsRemoved(subscription);
        assertFalse(new HashSet<>(subscriptionManager.listAll()).contains(subscription));
        assertEquals(0, subscription.getFailedPushCounter());
    }

    @Test
    public void testETPushError() {

        String url = "/push/error/et";
        stubFor(post(urlEqualTo(url))
                .withHeader("Content-Type", equalTo("application/xml"))
                .willReturn(aResponse()
                        .withStatus(500)
                        .withHeader("Content-Type", "text/plain")
                        .withBody("Internal server error")));

        Subscription subscription = createSubscription(url, "NSR:Quay:232", "NSR:Quay:125", null);
        verify(0, postRequestedFor(urlEqualTo(url)));
        assertEquals(0, subscription.getFailedPushCounter());
        HashSet<Subscription> subscriptions = new HashSet<>();
        subscriptions.add(subscription);

        EstimatedVehicleJourney estimatedVehicleJourney = new EstimatedVehicleJourney();
        subscriptionManager.notifySubscriptionsOnStops(subscriptions, estimatedVehicleJourney);
        waitAndVerifyAtLeast(1, postRequestedFor(urlEqualTo(url)));
        assertThat(dataStorageService.getSubscriptions(), hasItem(subscription));
        waitAndVerifyFailedPushCounter(1, subscription);

        OperatorRefStructure value = new OperatorRefStructure();
        value.setValue("NSB");
        estimatedVehicleJourney.setOperatorRef(value); //must add something so it differs from the previous ones
        subscriptionManager.notifySubscriptionsOnStops(subscriptions, estimatedVehicleJourney);
        waitAndVerifyAtLeast(2, postRequestedFor(urlEqualTo(url)));
        assertThat(dataStorageService.getSubscriptions(), hasItem(subscription));
        waitAndVerifyFailedPushCounter(2, subscription);

        estimatedVehicleJourney.setCancellation(false); //must add something so it differs from the previous ones
        subscriptionManager.notifySubscriptionsOnStops(subscriptions, estimatedVehicleJourney);
        waitAndVerifyAtLeast(3, postRequestedFor(urlEqualTo(url)));
        assertThat(dataStorageService.getSubscriptions(), hasItem(subscription));
        waitAndVerifyFailedPushCounter(3, subscription);

        estimatedVehicleJourney.setDataSource("blabla"); //must add something so it differs from the previous ones
        subscriptionManager.notifySubscriptionsOnStops(subscriptions, estimatedVehicleJourney);
        waitAndVerifyAtLeast(4, postRequestedFor(urlEqualTo(url)));
        waitAndVerifyFailedPushCounter(4, subscription);
        waitUntilSubscriptionIsRemoved(subscription);
    }

    @Test
    public void dontPushSameETMessageMoreThanOnce() throws JAXBException, XMLStreamException {

        String url = "/push/duplicates/et";
        stubFor(post(urlEqualTo(url))
                .withHeader("Content-Type", equalTo("application/xml"))
                .willReturn(aResponse()));

        Subscription subscription = createSubscription(url, "NSR:Quay:232", "NSR:Quay:125", null);
        verify(0, postRequestedFor(urlEqualTo(url)));
        assertEquals(0, subscription.getFailedPushCounter());
        assertTrue(alreadySentCache.keySet().isEmpty());
        HashSet<Subscription> subscriptions = new HashSet<>();
        subscriptions.add(subscription);
        EstimatedVehicleJourney estimatedVehicleJourney = createEstimatedVehicleJourney();
        //first notify
        subscriptionManager.notifySubscriptionsOnStops(subscriptions, estimatedVehicleJourney);
        waitAndVerifyAtLeast(1, postRequestedFor(urlEqualTo(url)));
        assertEquals(0, subscription.getFailedPushCounter());
        assertEquals(1, alreadySentCache.keySet().size());

        //second notify with same payload (but new instance) (should not be sent)
        EstimatedVehicleJourney estimatedVehicleJourney1 = createEstimatedVehicleJourney();
        //to demonstrate that equals and hashcode does not work for cxf generated objects...
        assertNotEquals(estimatedVehicleJourney, estimatedVehicleJourney1);
        assertNotEquals(estimatedVehicleJourney.hashCode(), estimatedVehicleJourney1.hashCode());
        subscriptionManager.notifySubscriptionsOnStops(subscriptions, estimatedVehicleJourney1);
        waitAndVerifyAtLeast(1, postRequestedFor(urlEqualTo(url)));
        assertEquals(0, subscription.getFailedPushCounter());
        assertEquals(1, alreadySentCache.keySet().size());

        //third notify with payload modified but not for subscribed stops (should not be sent)
        EstimatedCall notInterestingCall = estimatedVehicleJourney.getEstimatedCalls().getEstimatedCalls().get(1);
        assertEquals("Stop2", notInterestingCall.getStopPointNames().get(0).getValue());
        notInterestingCall.setExpectedDepartureTime(notInterestingCall.getExpectedDepartureTime().plusMinutes(1));
        subscriptionManager.notifySubscriptionsOnStops(subscriptions, estimatedVehicleJourney);
        waitAndVerifyAtLeast(1, postRequestedFor(urlEqualTo(url)));
        assertEquals(0, subscription.getFailedPushCounter());
        assertEquals(1, alreadySentCache.keySet().size());

        //fourth notify with payload modified for a subscribed stop (should be sent)
        EstimatedCall interestingCall = estimatedVehicleJourney.getEstimatedCalls().getEstimatedCalls().get(0);
        assertEquals("Stop1", interestingCall.getStopPointNames().get(0).getValue());
        interestingCall.setExpectedDepartureTime(interestingCall.getExpectedDepartureTime().plusMinutes(1));
        subscriptionManager.notifySubscriptionsOnStops(subscriptions, estimatedVehicleJourney);
        waitAndVerifyAtLeast(2, postRequestedFor(urlEqualTo(url)));
        assertEquals(0, subscription.getFailedPushCounter());
        assertEquals(2, alreadySentCache.keySet().size());
    }

    @Test
    public void dontPushSameSXMessageMoreThanOnce() throws JAXBException, XMLStreamException, InterruptedException {

        String url = "/push/duplicates/sx";
        stubFor(post(urlEqualTo(url))
                .withHeader("Content-Type", equalTo("application/xml"))
                .willReturn(aResponse()));

        Subscription subscription = createSubscription(url, "NSR:StopPlace:1", "NSR:StopPlace:2", null);
        verify(0, postRequestedFor(urlEqualTo(url)));
        assertEquals(0, subscription.getFailedPushCounter());
        assertTrue(alreadySentCache.keySet().isEmpty());
        HashSet<Subscription> subscriptions = new HashSet<>();
        subscriptions.add(subscription);
        PtSituationElement ptSituationElement1 = createPtSituationElement();
        //first notify
        subscriptionManager.notifySubscriptions(subscriptions, ptSituationElement1);
        waitAndVerifyAtLeast(1, postRequestedFor(urlEqualTo(url)));
        assertEquals(0, subscription.getFailedPushCounter());
        assertEquals(1, alreadySentCache.keySet().size());
        verify(1, postRequestedFor(urlEqualTo(url)));

        //second notify with same payload (but new instance) (should not be sent)
        PtSituationElement ptSituationElement2 = createPtSituationElement();
        assertNotEquals(ptSituationElement1.hashCode(), ptSituationElement2.hashCode());
        assertEquals(ptSituationElement1.getSituationNumber().getValue(), ptSituationElement2.getSituationNumber().getValue());
        assertEquals(ptSituationElement1.getVersion().getValue(), ptSituationElement2.getVersion().getValue());
        subscriptionManager.notifySubscriptions(subscriptions, ptSituationElement2);
        waitAndVerifyAtLeast(1, postRequestedFor(urlEqualTo(url)));
        assertEquals(0, subscription.getFailedPushCounter());
        assertEquals(1, alreadySentCache.keySet().size());
        verify(1, postRequestedFor(urlEqualTo(url)));

        //third notify with same ptSituationElement with version changed - should be sent
        PtSituationElement ptSituationElement3 = createPtSituationElement();
        SituationVersion version = new SituationVersion();
        version.setValue(BigInteger.valueOf(77));
        ptSituationElement3.setVersion(version);
        assertNotEquals(ptSituationElement1.hashCode(), ptSituationElement3.hashCode());
        assertEquals(ptSituationElement1.getSituationNumber().getValue(), ptSituationElement3.getSituationNumber().getValue());
        assertNotEquals(ptSituationElement1.getVersion().getValue(), ptSituationElement3.getVersion().getValue());
        subscriptionManager.notifySubscriptions(subscriptions, ptSituationElement3);
        waitAndVerifyAtLeast(2, postRequestedFor(urlEqualTo(url)));
        assertEquals(0, subscription.getFailedPushCounter());
        assertEquals(2, alreadySentCache.keySet().size());
        verify(2, postRequestedFor(urlEqualTo(url)));

        //fourth notify with same ptSituationElement and original version changed, but different validity (spec/profile says version should be changed if validity is changed!) - should NOT be sent
        PtSituationElement ptSituationElement4 = createPtSituationElement();
        HalfOpenTimestampOutputRangeStructure validity = ptSituationElement4.getValidityPeriods().get(0);
        validity.setEndTime(validity.getEndTime().plusHours(1));
        assertNotEquals(ptSituationElement1.hashCode(), ptSituationElement4.hashCode());
        assertEquals(ptSituationElement1.getSituationNumber().getValue(), ptSituationElement4.getSituationNumber().getValue());
        assertEquals(ptSituationElement1.getVersion().getValue(), ptSituationElement4.getVersion().getValue());
        assertNotEquals(ptSituationElement1.getValidityPeriods().get(0).getEndTime(), ptSituationElement4.getValidityPeriods().get(0).getEndTime());
        subscriptionManager.notifySubscriptions(subscriptions, ptSituationElement4);
        waitAndVerifyAtLeast(2, postRequestedFor(urlEqualTo(url)));
        assertEquals(0, subscription.getFailedPushCounter());
        Thread.sleep(100); //allow some time for a last call to be made...
        assertEquals(2, alreadySentCache.keySet().size());
        verify(2, postRequestedFor(urlEqualTo(url)));

    }

    @Test
    public void testSXPushMessagesForSubscriptionWithStops() throws JAXBException, XMLStreamException {

        String urlWithStop = "/push/1/sx";
        stubFor(post(urlEqualTo(urlWithStop))
                .withHeader("Content-Type", equalTo("application/xml"))
                .willReturn(aResponse()));
        Subscription subscriptionWithStop = createSubscription(urlWithStop, "NSR:StopPlace:1", "NSR:StopPlace:3", "NSB:Line:Line1");
        HashSet<Subscription> subscriptions = new HashSet<>();
        subscriptions.add(subscriptionWithStop);
        subscriptionManager.notifySubscriptions(subscriptions, createPtSituationElement());

        waitAndVerifyAtLeast(1, postRequestedFor(urlEqualTo(urlWithStop)));
        PtSituationElement msgForSubsciptionWithStops = getPushedPtSituationElement(urlWithStop);
        //For subscriptions with should all unsubscribed stops, and journeys with unsubscribed vehiclerefs og linerefs
        assertNotNull(msgForSubsciptionWithStops.getAffects());
        assertNotNull(msgForSubsciptionWithStops.getAffects().getVehicleJourneys());
        List<AffectedVehicleJourneyStructure> affectedVehicleJourneies = msgForSubsciptionWithStops.getAffects().getVehicleJourneys().getAffectedVehicleJourneies();
        assertNotNull(affectedVehicleJourneies);
        assertEquals(2, affectedVehicleJourneies.size());
        AffectedVehicleJourneyStructure affectedVehicleJourney = affectedVehicleJourneies.get(0);
        assertEquals("NSB:Line:Line1", affectedVehicleJourney.getLineRef().getValue());
        AffectedRouteStructure.StopPoints stopPoints = affectedVehicleJourney.getRoutes().get(0).getStopPoints();
        assertEquals(2, stopPoints.getAffectedStopPointsAndLinkProjectionToNextStopPoints().size());
    }

    @Test
    public void testSXPushMessageForSubscriptionWithCodespace() throws JAXBException, XMLStreamException {
        String urlWithStop = "/push/1/sx";
        stubFor(post(urlEqualTo(urlWithStop))
                .withHeader("Content-Type", equalTo("application/xml"))
                .willReturn(aResponse()));
        Subscription subscriptionWithStop = createSubscription(urlWithStop, null, null, null, "NSB");
        HashSet<Subscription> subscriptions = new HashSet<>();
        subscriptions.add(subscriptionWithStop);
        subscriptionManager.notifySubscriptions(subscriptions, createPtSituationElement());

        waitAndVerifyAtLeast(1, postRequestedFor(urlEqualTo(urlWithStop)));
        PtSituationElement msgForSubsciptionWithStops = getPushedPtSituationElement(urlWithStop);
        //For subscriptions with should all unsubscribed stops, and journeys with unsubscribed vehiclerefs og linerefs
        assertNotNull(msgForSubsciptionWithStops.getAffects());
        assertNotNull(msgForSubsciptionWithStops.getAffects().getVehicleJourneys());
        List<AffectedVehicleJourneyStructure> affectedVehicleJourneies = msgForSubsciptionWithStops.getAffects().getVehicleJourneys().getAffectedVehicleJourneies();
        assertNotNull(affectedVehicleJourneies);
        assertEquals(3, affectedVehicleJourneies.size());
    }

    @Test
    public void testSXPushMessagesForSubscriptionWithoutStops() throws JAXBException, XMLStreamException {

        String urlWithoutStop = "/push/2/sx";
        stubFor(post(urlEqualTo(urlWithoutStop))
                .withHeader("Content-Type", equalTo("application/xml"))
                .willReturn(aResponse()));
        Subscription subscriptionWithoutStop = createSubscription(urlWithoutStop, null, null, "NSB:Line:Line1");

        HashSet<Subscription> subscriptions = new HashSet<>();
        subscriptions.add(subscriptionWithoutStop);
        subscriptionManager.notifySubscriptions(subscriptions, createPtSituationElement());


        waitAndVerifyAtLeast(1, postRequestedFor(urlEqualTo(urlWithoutStop)));
        PtSituationElement msgForSubsciptionWithoutStops  = getPushedPtSituationElement(urlWithoutStop);
        //For subscriptions without stops we remove all journeys not subscribed upon: vehicle(journey)ref og lineref<-added by us from live routes, not the producer
        assertNotNull(msgForSubsciptionWithoutStops .getAffects());
        assertNotNull(msgForSubsciptionWithoutStops .getAffects().getVehicleJourneys());
        List<AffectedVehicleJourneyStructure> affectedJourneies = msgForSubsciptionWithoutStops .getAffects().getVehicleJourneys().getAffectedVehicleJourneies();
        assertNotNull(affectedJourneies);
        assertEquals(2, affectedJourneies.size());
        AffectedVehicleJourneyStructure journey1234;
        AffectedVehicleJourneyStructure journey2222;
        if ("1234".equals(affectedJourneies.get(0).getVehicleJourneyReves().get(0).getValue())) {
            journey1234 = affectedJourneies.get(0);
            journey2222 = affectedJourneies.get(1);
        } else {
            journey2222 = affectedJourneies.get(0);
            journey1234 = affectedJourneies.get(1);
        }
        assertEquals("NSB:Line:Line1", journey1234.getLineRef().getValue());
        assertEquals("1234", journey1234.getVehicleJourneyReves().get(0).getValue());
        assertEquals(4, journey1234.getRoutes().get(0).getStopPoints().getAffectedStopPointsAndLinkProjectionToNextStopPoints().size());
        assertEquals("NSB:Line:Line1", journey2222.getLineRef().getValue());
        assertEquals("2222", journey2222.getVehicleJourneyReves().get(0).getValue());
        assertEquals(3, journey2222.getRoutes().get(0).getStopPoints().getAffectedStopPointsAndLinkProjectionToNextStopPoints().size());

    }


    @Test
    public void testHeartbeatAndTermination() throws Exception {
        DatatypeFactory datatypeFactory = DatatypeFactory.newInstance();
        ZonedDateTime start = ZonedDateTime.now();
        createStubAndSiriSubscription("s1", datatypeFactory.newDuration("PT1M"), start.plusHours(1));
        createStubAndSiriSubscription("s2", datatypeFactory.newDuration("PT2M"), null);
        createStubAndSiriSubscription("s3", null ,null);
        RequestPatternBuilder s1RequestPattern = postRequestedFor(urlEqualTo("/heartbeat/s1"));
        RequestPatternBuilder s2RequestPattern = postRequestedFor(urlEqualTo("/heartbeat/s2"));
        RequestPatternBuilder s3RequestPattern = postRequestedFor(urlEqualTo("/heartbeat/s3"));

        expectReceived(0, s1RequestPattern);
        expectReceived(0, s2RequestPattern);
        expectReceived(0, s3RequestPattern);

        //no notifications first time:
        subscriptionManager.handleHeartbeatAndTermination(start);
        waitNoActivePushThreads();
        expectReceived(0, s1RequestPattern);
        expectReceived(0, s2RequestPattern);
        expectReceived(0, s3RequestPattern);

        //no notifications after 30 sec
        subscriptionManager.handleHeartbeatAndTermination(start.plusSeconds(30));
        waitNoActivePushThreads();
        expectReceived(0, s1RequestPattern);
        expectReceived(0, s2RequestPattern);
        expectReceived(0, s3RequestPattern);

        //s1 received one notification after 61 sec, but no others (the extra second to make sure we're not too fast...)
        subscriptionManager.handleHeartbeatAndTermination(start.plusSeconds(61));
        waitNoActivePushThreads();
        waitAndVerifyNotificationsInOrder(s1RequestPattern, HeartbeatNotificationStructure.class);
        expectReceived(0, s2RequestPattern);
        expectReceived(0, s3RequestPattern);

        //no new notifications after 90 secs
        subscriptionManager.handleHeartbeatAndTermination(start.plusSeconds(90));
        waitNoActivePushThreads();
        expectReceived(1, s1RequestPattern);
        expectReceived(0, s2RequestPattern);
        expectReceived(0, s3RequestPattern);

        //both s1 and s2 receive a notification after a little over 2 minutes (the extra seconds to make sure we're not too fast...)
        subscriptionManager.handleHeartbeatAndTermination(start.plusSeconds(122));
        waitNoActivePushThreads();
        waitAndVerifyNotificationsInOrder(s1RequestPattern, HeartbeatNotificationStructure.class, HeartbeatNotificationStructure.class);
        waitAndVerifyNotificationsInOrder(s2RequestPattern, HeartbeatNotificationStructure.class);
        expectReceived(0, s3RequestPattern);

        //after 1 hour s1 should be terminated, s2 should receieve a new heartbeat
        subscriptionManager.handleHeartbeatAndTermination(start.plusMinutes(61));
        waitNoActivePushThreads();
        waitAndVerifyNotificationsInOrder(s1RequestPattern, HeartbeatNotificationStructure.class, HeartbeatNotificationStructure.class, SubscriptionTerminatedNotificationStructure.class);
        waitAndVerifyNotificationsInOrder(s2RequestPattern, HeartbeatNotificationStructure.class, HeartbeatNotificationStructure.class);
        expectReceived(0, s3RequestPattern);

        //after that s1 recieved no more, but s2 receives a heartbeat every 2 minutes
        subscriptionManager.handleHeartbeatAndTermination(start.plusMinutes(64));
        waitNoActivePushThreads();
        waitAndVerifyNotificationsInOrder(s1RequestPattern, HeartbeatNotificationStructure.class, HeartbeatNotificationStructure.class, SubscriptionTerminatedNotificationStructure.class);
        waitAndVerifyNotificationsInOrder(s2RequestPattern, HeartbeatNotificationStructure.class, HeartbeatNotificationStructure.class, HeartbeatNotificationStructure.class);
        expectReceived(0, s3RequestPattern);
    }

    private void waitNoActivePushThreads() {
        long start = System.currentTimeMillis();
        while (subscriptionManager.getActivePushThreads() > 0) {
            if (System.currentTimeMillis() - start > 10000) {
                fail("has waited too long for subscriptionManager.getActivePushThreads() to reach 0...");
            }
        }
    }

    private void createStubAndSiriSubscription(String requestor, Duration heartbeatInterval, ZonedDateTime initialTerminationTime) {
        String url = "/heartbeat/"+requestor;
        stubFor(post(urlEqualTo(url))
                .withHeader("Content-Type", equalTo("application/xml"))
                .willReturn(aResponse()));
        Subscription subscription = new Subscription();
        subscription.setUseSiriSubscriptionModel(true);
        subscription.setName(Subscription.getName(requestor, "1"));
        subscription.setHeartbeatInterval(heartbeatInterval);
        subscription.setInitialTerminationTime(initialTerminationTime);
        subscription.setPushAddress("http://localhost:" + wireMockRule.port() + url);
        subscription.addCodespace("TEST");
        subscriptionManager.addOrUpdate(subscription, true);
    }


    private PtSituationElement getPushedPtSituationElement(String urlWithStop) throws JAXBException, XMLStreamException {
        List<LoggedRequest> loggedRequests = findAll(postRequestedFor(urlEqualTo(urlWithStop)));
        assertEquals(1, loggedRequests.size());
        LoggedRequest loggedRequest = loggedRequests.get(0);
        String xml = new String(loggedRequest.getBody());
        return siriMarshaller.unmarshall(xml, PtSituationElement.class);
    }

    private PtSituationElement createPtSituationElement() throws JAXBException, XMLStreamException {
        String xml ="<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n" +
                "<PtSituationElement xmlns=\"http://www.siri.org.uk/siri\">\n" +
                "  <CreationTime>2018-01-19T10:01:05+01:00</CreationTime>\n" +
                "  <ParticipantRef>NSB</ParticipantRef>\n" +
                "  <SituationNumber>status-167911766</SituationNumber>\n" +
                "  <Version>1</Version>\n" +
                "  <Source>\n" +
                "    <SourceType>web</SourceType>\n" +
                "  </Source>\n" +
                "  <Progress>published</Progress>\n" +
                "  <ValidityPeriod>\n" +
                "    <StartTime>2018-01-19T00:00:00+01:00</StartTime>\n" +
                "    <EndTime>2018-01-19T16:54:00+01:00</EndTime>\n" +
                "  </ValidityPeriod>\n" +
                "  <UndefinedReason/>\n" +
                "  <ReportType>incident</ReportType>\n" +
                "  <Keywords/>\n" +
                "  <Description xml:lang=\"NO\">Toget er innstilt mellom Oslo S og Drammen. Vennligst benytt neste tog.</Description>\n" +
                "  <Description xml:lang=\"EN\">The train is cancelled between Oslo S and Drammen. Passengers are requested to take the next train.</Description>\n" +
                "  <Affects>\n" +
                "    <VehicleJourneys>\n" +
                "      <AffectedVehicleJourney>\n" +
                "        <LineRef>NSB:Line:Line1</LineRef>\n" +
                "        <VehicleJourneyRef>1234</VehicleJourneyRef>\n" +
                "        <Route>\n" +
                "          <StopPoints>\n" +
                "            <AffectedOnly>true</AffectedOnly>\n" +
                "            <AffectedStopPoint>\n" +
                "              <StopPointRef>NSR:StopPlace:1</StopPointRef>\n" +
                "              <StopPointName>Oslo S</StopPointName>\n" +
                "            </AffectedStopPoint>\n" +
                "            <AffectedStopPoint>\n" +
                "              <StopPointRef>NSR:StopPlace:2</StopPointRef>\n" +
                "              <StopPointName>Nationaltheatret</StopPointName>\n" +
                "            </AffectedStopPoint>\n" +
                "            <AffectedStopPoint>\n" +
                "              <StopPointRef>NSR:StopPlace:3</StopPointRef>\n" +
                "              <StopPointName>Skøyen</StopPointName>\n" +
                "            </AffectedStopPoint>\n" +
                "            <AffectedStopPoint>\n" +
                "              <StopPointRef>NSR:StopPlace:4</StopPointRef>\n" +
                "              <StopPointName>Lysaker</StopPointName>\n" +
                "            </AffectedStopPoint>\n" +
                "          </StopPoints>\n" +
                "        </Route>\n" +
                "      </AffectedVehicleJourney>\n" +
                "      <AffectedVehicleJourney>\n" +
                "        <LineRef>NSB:Line:Line1</LineRef>\n" +
                "        <VehicleJourneyRef>2222</VehicleJourneyRef>\n" +
                "        <Route>\n" +
                "          <StopPoints>\n" +
                "            <AffectedOnly>true</AffectedOnly>\n" +
                "            <AffectedStopPoint>\n" +
                "              <StopPointRef>NSR:StopPlace:1</StopPointRef>\n" +
                "              <StopPointName>Oslo S</StopPointName>\n" +
                "            </AffectedStopPoint>\n" +
                "            <AffectedStopPoint>\n" +
                "              <StopPointRef>NSR:StopPlace:2</StopPointRef>\n" +
                "              <StopPointName>Nationaltheatret</StopPointName>\n" +
                "            </AffectedStopPoint>\n" +
                "            <AffectedStopPoint>\n" +
                "              <StopPointRef>NSR:StopPlace:3</StopPointRef>\n" +
                "              <StopPointName>Skøyen</StopPointName>\n" +
                "            </AffectedStopPoint>\n" +
                "          </StopPoints>\n" +
                "        </Route>\n" +
                "      </AffectedVehicleJourney>\n" +
                "      <AffectedVehicleJourney>\n" +
                "        <LineRef>NSB:Line:Line2</LineRef>\n" +
                "        <VehicleJourneyRef>4444</VehicleJourneyRef>\n" +
                "        <Route>\n" +
                "          <StopPoints>\n" +
                "            <AffectedOnly>true</AffectedOnly>\n" +
                "            <AffectedStopPoint>\n" +
                "              <StopPointRef>NSR:StopPlace:2</StopPointRef>\n" +
                "              <StopPointName>Nationaltheatret</StopPointName>\n" +
                "            </AffectedStopPoint>\n" +
                "          </StopPoints>\n" +
                "        </Route>\n" +
                "      </AffectedVehicleJourney>\n" +
                "    </VehicleJourneys>\n" +
                "  </Affects>\n" +
                "</PtSituationElement>";
        return siriMarshaller.unmarshall(xml, PtSituationElement.class);
    }

    private EstimatedVehicleJourney createEstimatedVehicleJourney() throws JAXBException, XMLStreamException {
        String xml ="<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n" +
                "<EstimatedVehicleJourney xmlns=\"http://www.siri.org.uk/siri\" xmlns:ns2=\"http://www.ifopt.org.uk/acsb\" xmlns:ns4=\"http://datex2.eu/schema/2_0RC1/2_0\" xmlns:ns3=\"http://www.ifopt.org.uk/ifopt\">\n" +
                "    <LineRef>NSB:Line:L123</LineRef>\n" +
                "    <DirectionRef>Test</DirectionRef>\n" +
                "    <DatedVehicleJourneyRef>2118:2018-02-08</DatedVehicleJourneyRef>\n" +
                "    <VehicleMode>rail</VehicleMode>\n" +
                "    <OriginName>Asker</OriginName>\n" +
                "    <OriginShortName>ASR</OriginShortName>\n" +
                "    <OperatorRef>NSB</OperatorRef>\n" +
                "    <ProductCategoryRef>Lt</ProductCategoryRef>\n" +
                "    <ServiceFeatureRef>passengerTrain</ServiceFeatureRef>\n" +
                "    <VehicleRef>2118</VehicleRef>\n" +
                "    <EstimatedCalls>\n" +
                "        <EstimatedCall>\n" +
                "            <StopPointRef>NSR:Quay:232</StopPointRef>\n" +
                "            <StopPointName>Stop1</StopPointName>\n" +
                "            <RequestStop>false</RequestStop>\n" +
                "            <ArrivalBoardingActivity>alighting</ArrivalBoardingActivity>\n" +
                "            <AimedDepartureTime>2018-02-08T08:48:00+01:00</AimedDepartureTime>\n" +
                "            <ExpectedDepartureTime>2018-02-08T09:00:48+01:00</ExpectedDepartureTime>\n" +
                "            <DepartureStatus>delayed</DepartureStatus>\n" +
                "            <DeparturePlatformName>6</DeparturePlatformName>\n" +
                "            <DepartureBoardingActivity>boarding</DepartureBoardingActivity>\n" +
                "        </EstimatedCall>\n" +
                "        <EstimatedCall>\n" +
                "            <StopPointRef>NSR:Quay:444</StopPointRef>\n" +
                "            <StopPointName>Stop2</StopPointName>\n" +
                "            <RequestStop>false</RequestStop>\n" +
                "            <ArrivalBoardingActivity>alighting</ArrivalBoardingActivity>\n" +
                "            <AimedDepartureTime>2018-02-08T08:49:00+01:00</AimedDepartureTime>\n" +
                "            <ExpectedDepartureTime>2018-02-08T10:00:48+01:00</ExpectedDepartureTime>\n" +
                "            <DepartureStatus>delayed</DepartureStatus>\n" +
                "            <DeparturePlatformName>6</DeparturePlatformName>\n" +
                "            <DepartureBoardingActivity>boarding</DepartureBoardingActivity>\n" +
                "        </EstimatedCall>\n" +
                "        <EstimatedCall>\n" +
                "            <StopPointRef>NSR:Quay:125</StopPointRef>\n" +
                "            <StopPointName>Stop3</StopPointName>\n" +
                "            <RequestStop>false</RequestStop>\n" +
                "            <AimedArrivalTime>2018-02-08T09:23:00+01:00</AimedArrivalTime>\n" +
                "            <ExpectedArrivalTime>2018-02-08T09:29:08+01:00</ExpectedArrivalTime>\n" +
                "            <ArrivalStatus>delayed</ArrivalStatus>\n" +
                "            <ArrivalPlatformName>9</ArrivalPlatformName>\n" +
                "            <ArrivalBoardingActivity>alighting</ArrivalBoardingActivity>\n" +
                "            <AimedDepartureTime>2018-02-08T09:26:00+01:00</AimedDepartureTime>\n" +
                "            <ExpectedDepartureTime>2018-02-08T09:29:38+01:00</ExpectedDepartureTime>\n" +
                "            <DepartureStatus>delayed</DepartureStatus>\n" +
                "            <DeparturePlatformName>9</DeparturePlatformName>\n" +
                "            <DepartureBoardingActivity>boarding</DepartureBoardingActivity>\n" +
                "        </EstimatedCall>\n" +
                "    </EstimatedCalls>\n" +
                "</EstimatedVehicleJourney>";
        return siriMarshaller.unmarshall(xml, EstimatedVehicleJourney.class);
    }

    private Subscription createSubscription(String pushAddress, String from, String to, String line) {
        return createSubscription(pushAddress, from, to, line, null);
    }

    private Subscription createSubscription(String pushAddress, String from, String to, String line, String codespace) {
        Subscription subscription = new Subscription();
        if (from != null) subscription.addFromStopPoint(from);
        if (to != null) subscription.addToStopPoint(to);
        if (line != null) subscription.addLineRef(line);
        if (codespace != null) subscription.addCodespace(codespace);
        subscription.setName("Push over http test");
        pushAddress = pushAddress.substring(0, pushAddress.length()-3); //last '/et' (or '/sx') is added by the subscription manager
        subscription.setPushAddress("http://localhost:" + wireMockRule.port() + pushAddress);
        return subscriptionManager.addOrUpdate(subscription);
    }

    private void waitAndVerifyFailedPushCounter(int expected, Subscription subscription) {
        long start = System.currentTimeMillis();
        long actual = 0;
        while (System.currentTimeMillis() - start < 10000) {
            actual = subscription.getFailedPushCounter();
            if (actual > expected) {
                fail("Expected " + expected + " found " + actual);
            }
            if (actual == expected) {
                return;
            }
        }
        fail("Expected " + expected + " but found only " + actual+" before we timed out...");
    }

    /**
     * Since messages are pushed asynchronously, we must allow some waiting before we can verify receival.
     */
    private void waitAndVerifyAtLeast(int expected, RequestPatternBuilder requestPatternBuilder) {
        long start = System.currentTimeMillis();
        int actual = 0;
        while (System.currentTimeMillis() - start < 10000) {
            List<LoggedRequest> all = findAll(requestPatternBuilder);
            actual = all.size();
            if (actual > expected) {
                expectReceived(expected, requestPatternBuilder);
            }
            if (actual == expected) {
                return;
            }
        }
        fail("Expected " + expected + " but found only " + actual+" before we timed out...");
    }

    private void waitAndVerifyNotificationsInOrder(RequestPatternBuilder requestPatternBuilder, Class... notifications) throws Exception {
        waitAndVerifyAtLeast(notifications.length, requestPatternBuilder);
        List<LoggedRequest> all = findAll(requestPatternBuilder);
        for (int i = 0; i < all.size(); i++) {
            LoggedRequest loggedRequest = all.get(i);
            Class notification = notifications[i];
            Siri siri = siriMarshaller.unmarshall(loggedRequest.getBodyAsString(), Siri.class);
            if (HeartbeatNotificationStructure.class.equals(notification)) {
                assertNotNull(siri.getHeartbeatNotification());
                assertNull(siri.getSubscriptionTerminatedNotification());
            } else if (SubscriptionTerminatedNotificationStructure.class.equals(notification)) {
                assertNotNull(siri.getSubscriptionTerminatedNotification());
                assertNull(siri.getHeartbeatNotification());
            } else {
                fail("Unhandled (in this test assertion at least) notification class: "+notification.getSimpleName());
            }
        }

    }

    private void expectReceived(int expected, RequestPatternBuilder requestPatternBuilder) {
        List<LoggedRequest> all = findAll(requestPatternBuilder);
        if (expected != all.size()) {
            String url = requestPatternBuilder.build().getUrl();
            StringBuilder sb = new StringBuilder("mock('"+url+"'): Expected "+expected+", but has received "+all.size());
            if (all.size() > 0) sb.append(":\n");
            for (LoggedRequest loggedRequest : all) {
                sb.append("\n");
                sb.append(prettyPrintSiri(loggedRequest.getBodyAsString()));
                sb.append("\n");
            }
            fail(sb.toString());
        }
    }

    private String prettyPrintSiri(String bodyAsString) {
        try {
            Siri siri = siriMarshaller.unmarshall(bodyAsString, Siri.class);
            return siriMarshaller.prettyPrintNoNamespaces(siri);
        } catch (Exception e) {
            e.printStackTrace();
            return bodyAsString;
        }
    }

    private void waitUntilSubscriptionIsRemoved(Subscription subscription) {
        long start = System.currentTimeMillis();
        while (System.currentTimeMillis() - start < 10000) {
            Collection<Subscription> subscriptions = dataStorageService.getSubscriptions();
            if (!subscriptions.contains(subscription)) {
                return;
            }
        }
        assertThat(dataStorageService.getSubscriptions(), CoreMatchers.not(hasItem(subscription)));
    }
}