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

package org.entur.ukur.routedata;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.lang3.StringUtils;
import org.entur.ukur.service.MetricsService;
import org.entur.ukur.service.QuayAndStopPlaceMappingService;
import org.junit.Ignore;
import org.junit.Test;
import org.rutebanken.netex.model.JourneyPattern;
import org.rutebanken.netex.model.PointInLinkSequence_VersionedChildStructure;
import org.rutebanken.netex.model.ServiceJourney;
import org.rutebanken.netex.model.StopPointInJourneyPattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.*;

import static org.entur.ukur.xml.SiriObjectHelper.getStringValue;
import static org.junit.Assert.assertTrue;

@Ignore
public class NeTExReaderTest {
    private final Logger logger = LoggerFactory.getLogger(getClass());

    @Test
    public void readNSB_NeTEx_withStopplaces() throws Exception {
        LoggerContext logCtx = (LoggerContext) LoggerFactory.getILoggerFactory();
        logCtx.getLogger("org.entur").setLevel(Level.INFO);

        readNeTEx("/home/jon/Documents/Entur/rb_nsb-aggregated-netex.zip", true);
    }

    @Test
    public void readNSB_NeTEx_withQuays() throws Exception {
        readNeTEx("/home/jon/Documents/Entur/rb_nsb-aggregated-netex.zip", false);
    }

    @Test
    public void readNorwayNeTEx() throws Exception {
        //disables trace logging as there is lots of service journeys:
        LoggerContext logCtx = (LoggerContext) LoggerFactory.getILoggerFactory();
        logCtx.getLogger("org.entur").setLevel(Level.INFO);

        readNeTEx("/home/jon/Documents/Entur/rb_norway-aggregated-netex.zip", false);
    }

    private void readNeTEx(String path, boolean convertQuayToStopplace) throws Exception {

        long start = System.currentTimeMillis();
        File netexFile = new File(path);
        assertTrue(netexFile.exists());
        logger.info("Reading NeTEx-data from {} - filesize: {} bytes", path, String.format("%,d", netexFile.length()));

        NetexProcessor processor = new NetexProcessor(netexFile);
        processor.loadFiles();
        logger.info("NetexProcessor completed after {} ms", String.format("%,d", (System.currentTimeMillis()-start)));

        Map<String, Set<String>> trainNumberTrips = new HashMap<>();
        Map<String, Set<String>> lineRefTrips = new HashMap<>();
        Set<String> uniqueOperators = new HashSet<>();

        for (ServiceJourney serviceJourney : processor.serviceJourneyByPatternId.values()) {

            String serviceJourneyId = serviceJourney.getId();
            String privateCode = getStringValue(serviceJourney.getPrivateCode());
            String lineRef = serviceJourney.getLineRef().getValue().getRef();
            String journeyPatternRef = serviceJourney.getJourneyPatternRef().getValue().getRef();
            String operator = null;
            if (serviceJourney.getOperatorRef() != null && StringUtils.isNotBlank(serviceJourney.getOperatorRef().getRef())) {
                operator = serviceJourney.getOperatorRef().getRef();
                uniqueOperators.add(operator);
            }
            logger.trace("serviceJourneyId: {}", serviceJourneyId);
            logger.trace("lineRef: {}", lineRef);
            logger.trace("privateCode (trainnumber): {}", privateCode);
            logger.trace("journeyPatternRef: {}", journeyPatternRef);
            logger.trace("operator: {}", operator);

            JourneyPattern journeyPattern = processor.journeyPatternsById.get(journeyPatternRef);
            List<PointInLinkSequence_VersionedChildStructure> pointInJourneyPattern = journeyPattern.getPointsInSequence().getPointInJourneyPatternOrStopPointInJourneyPatternOrTimingPointInJourneyPattern();
            StringBuilder quayRouteAsString = new StringBuilder();
            for (PointInLinkSequence_VersionedChildStructure point : pointInJourneyPattern) {
                StopPointInJourneyPattern stopPointInJourneyPattern = (StopPointInJourneyPattern)point;
                String ref = stopPointInJourneyPattern.getScheduledStopPointRef().getValue().getRef();
                String quayId = processor.quayIdByStopPointRef.get(ref);
                logger.trace("- stop={}, order={}, scheduledStopPointRef={}, quayId={}", point.getId(), point.getOrder(), ref, quayId);
                if (quayRouteAsString.length() > 0) {
                    quayRouteAsString.append(", ");
                }
                quayRouteAsString.append(convertQuayToStopplace ? getStopplace(quayId) : quayId);
            }

            if (StringUtils.isNotBlank(privateCode) && StringUtils.startsWith(operator, "NSB:")) {
                Set<String> tripsForTrainNumber = trainNumberTrips.getOrDefault(privateCode, new HashSet<>());
                tripsForTrainNumber.add(quayRouteAsString.toString());
                trainNumberTrips.put(privateCode, tripsForTrainNumber);
            }
            //Alle
            Set<String> tripsForLineRef = lineRefTrips.getOrDefault(lineRef, new HashSet<>());
            tripsForLineRef.add(quayRouteAsString.toString());
            lineRefTrips.put(lineRef, tripsForLineRef);
        }




        logger.info("Total processing took {} ms", String.format("%,d", (System.currentTimeMillis()-start)));
        logger.info("  number of serviceJourneys: {}", processor.serviceJourneyByPatternId.size());
        logger.info("  number of unique operators: {}: {}", uniqueOperators.size(), uniqueOperators);

        logger.info("  trainNumberTrips.size(): {}", trainNumberTrips.size());
        logNoUniqueStopSequences("trainNumber", trainNumberTrips);
        if (trainNumberTrips.size() < 100) {
            logger.info("  trainNumberTrips keys  : {}", trainNumberTrips.keySet());
            logger.info("  trainNumberTrips values: {}", trainNumberTrips);
        }

        logger.info("  lineRefTrips.size(): {}", lineRefTrips.size());
        logNoUniqueStopSequences("lineRef", lineRefTrips);
        if (trainNumberTrips.size() < 100) {
            logger.info("  lineRefTrips keys  : {}", lineRefTrips.keySet());
            logger.info("  lineRefTrips values: {}", lineRefTrips);
        }



//        long t1 = System.currentTimeMillis();
//        int diffCounter = 0;
//        List<DatedServiceJourney> changes = new ArrayList<>();
//        for (org.rutebanken.netex.model.ServiceJourney serviceJourney : processor.serviceJourneyByPatternId.values()) {
//
//            String serviceJourneyId = serviceJourney.getId();
//
//            String version = serviceJourney.getVersion();
//
//            JourneyPattern journeyPattern = processor.journeyPatternsById.get(serviceJourney.getJourneyPatternRef().getValue().getRef());
//            List<PointInLinkSequence_VersionedChildStructure> pointInJourneyPattern = journeyPattern.getPointsInSequence().getPointInJourneyPatternOrStopPointInJourneyPatternOrTimingPointInJourneyPattern();
//            PointInLinkSequence_VersionedChildStructure firstScheduledStopPoint = pointInJourneyPattern.get(0);
//
//            String scheduledStopPointRef = ((StopPointInJourneyPattern) firstScheduledStopPoint).getScheduledStopPointRef().getValue().getRef();
//
////                String firstQuay = processor.quayIdByStopPointRef.get(scheduledStopPointRef);
//
//            String lineRef = serviceJourney.getLineRef().getValue().getRef();
//
//            String departureTime = serviceJourney.getPassingTimes().getTimetabledPassingTime().get(0).getDepartureTime().format(timeFormatter);
//
//            DayTypeRefs_RelStructure dayTypes = serviceJourney.getDayTypes();
//            for (JAXBElement<? extends DayTypeRefStructure> dayTypeRef : dayTypes.getDayTypeRef()) {
//
//                DayType dayType = processor.dayTypeById.get(dayTypeRef.getValue().getRef());
//
//                DayTypeAssignment dayTypeAssignment = processor.dayTypeAssignmentByDayTypeId.get(dayType.getId());
//
//                String departureDate = dayTypeAssignment.getDate().format(dateFormatter);
//
//                String privateCode = serviceJourney.getPrivateCode().getValue();
//
////                    ServiceJourney currentServiceJourney = new ServiceJourney(serviceJourneyId, version, privateCode, lineRef, departureDate, departureTime);
//
////                    DatedServiceJourney current = serviceJourneyRepository.findByServiceJourneyIdAndDepartureDateAndVersion(serviceJourneyId, departureDate, version);
////                    if (current == null) {
//
//
//            }
//        }
    }

    private static QuayAndStopPlaceMappingService quayAndStopPlaceMappingService = null;
    private String getStopplace(String quayId) throws Exception {
        if (quayAndStopPlaceMappingService == null) { //a primitive singleton here...
            quayAndStopPlaceMappingService = new QuayAndStopPlaceMappingService(new MetricsService());
            InputStream json = new FileInputStream("/tmp/StopPlacesAndQuays.json");
//            InputStream json = new FileInputStream("/home/jon/Documents/Entur/StopPlacesAndQuays.json");
            ObjectMapper mapper = new ObjectMapper();
            //noinspection unchecked
            HashMap<String, Collection<String>> result = mapper.readValue(json, HashMap.class);
            quayAndStopPlaceMappingService.updateStopsAndQuaysMap(result);
            HashMap<String, Collection<String>> captured = quayAndStopPlaceMappingService.getAllStopPlaces();
            assertTrue(captured.size() > 10000);
        }
        return quayAndStopPlaceMappingService.mapQuayToStopPlace(quayId);
    }

    private void logNoUniqueStopSequences(String name, Map<String, Set<String>> tripsToCheck) {
        Map<Integer, Set<String>> numberOfTrips = new HashMap<>();
        for (Map.Entry<String, Set<String>> entry : tripsToCheck.entrySet()) {
            int key = entry.getValue().size();
            Set<String> trips = numberOfTrips.getOrDefault(key, new HashSet<>());
            trips.add(entry.getKey());
            numberOfTrips.put(key, trips);
        }
        ArrayList<Integer> keys = new ArrayList<>(numberOfTrips.keySet());
        keys.sort(Integer::compareTo);
        logger.info("  number of uniqe trips per {}: ", name);
        for (Integer key : keys) {
            logger.info("  * {} {}s has {} unique stop sequences", numberOfTrips.get(key).size(), name, key);
        }
    }


}