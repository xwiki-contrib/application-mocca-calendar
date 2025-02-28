/*
 * See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package org.xwiki.contrib.moccacalendar.script;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Set;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Provider;
import javax.inject.Singleton;

import org.joda.time.DateTime;
import org.slf4j.Logger;
import org.xwiki.component.annotation.Component;
import org.xwiki.contrib.moccacalendar.EventInstance;
import org.xwiki.contrib.moccacalendar.RecurrentEventGenerator;
import org.xwiki.contrib.moccacalendar.internal.EventConstants;
import org.xwiki.contrib.moccacalendar.internal.Utils;
import org.xwiki.model.EntityType;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.model.reference.DocumentReferenceResolver;
import org.xwiki.model.reference.EntityReference;
import org.xwiki.model.reference.EntityReferenceProvider;
import org.xwiki.model.reference.EntityReferenceSerializer;
import org.xwiki.model.reference.SpaceReference;
import org.xwiki.query.Query;
import org.xwiki.query.QueryException;
import org.xwiki.query.QueryFilter;
import org.xwiki.query.QueryManager;
import org.xwiki.rendering.syntax.Syntax;
import org.xwiki.script.service.ScriptService;
import org.xwiki.security.authorization.AuthorizationManager;
import org.xwiki.security.authorization.Right;

import com.xpn.xwiki.XWikiContext;
import com.xpn.xwiki.XWikiException;
import com.xpn.xwiki.api.Document;
import com.xpn.xwiki.doc.XWikiDocument;
import com.xpn.xwiki.objects.BaseObject;

/**
 * Script service to obtain information about calendars and event instances.
 *
 * @version $Id: $
 * @since 2.7
 */
@Named("moccacalendar")
@Singleton
@Component
public class MoccaCalendarScriptService implements ScriptService
{
    private static final String BASE_QUERY_PREFIX = ", BaseObject as obj, IntegerProperty as recurrent,"
        + " DateProperty as startdate, DateProperty as enddate"
        + " where obj.id=startdate.id.id and startdate.id.name='startDate'"
        + " and obj.id=enddate.id.id and enddate.id.name='endDate'"
        + " and obj.id=recurrent.id.id and recurrent.id.name='recurrent'"
        + " and doc.fullName=obj.name and doc.fullName!='MoccaCalendar.MoccaCalendarEventTemplate'"
        + " and obj.className='" + EventConstants.MOCCA_CALENDAR_EVENT_CLASS_NAME + "'";
    private static final String CALENDAR_BASE_QUERY = ", BaseObject as obj"
        + " where doc.fullName=obj.name and doc.name!='MoccaCalendarTemplate'" + " and obj.className='"
        + EventConstants.MOCCA_CALENDAR_CLASS_NAME + "' order by doc.title, doc.name";

    private static final String FILTER_WIKI = "wiki";
    private static final String FILTER_SPACE = "space";
    private static final String FILTER_PAGE = "page";

    /**
     * a small helper class to keep the data for a HQL query.
     */
    private static class QueryData
    {
        private final StringBuilder hql = new StringBuilder();
        private final Map<String, Object> queryParams = new HashMap<>();
        
        /**
         * build the hql query.
         * @return the string builded to construct the query
         */
        public StringBuilder getHql()
        {
            return hql;
        }

        /**
         * the parameters for the query.
         * @return a map of parameters
         */
        Map<String, Object> getQueryParams()
        {
            return queryParams;
        }
    }

    @Inject
    private AuthorizationManager authorizationManager;

    @Inject
    private Provider<XWikiContext> xcontextProvider;

    @Inject
    @Named("currentmixed")
    private DocumentReferenceResolver<String> stringDocRefResolver;

    @Inject
    @Named("compact")
    private EntityReferenceSerializer<String> compactWikiSerializer;
    // other code uses @Named("local") - which omits wiki name ?

    @Inject
    private QueryManager queryManager;

    @Inject
    private EntityReferenceProvider defaultEntityReferenceProvider;

    @Inject
    @Named("hidden")
    private QueryFilter hidden;

    @Inject
    private Map<String, RecurrentEventGenerator> eventGenerators;

    @Inject
    private Logger logger;

    /**
     * get all calendars.
     * @return a list of document references point to pages containing calendar objects.
     */
    public List<DocumentReference> getAllCalendars()
    {
        List<DocumentReference> calenderRefs = Collections.emptyList();

        try {
            Query query = queryManager.createQuery(CALENDAR_BASE_QUERY, Query.HQL);
            List<String> results = query.execute();
            calenderRefs = filterViewableEvents(results);
        } catch (QueryException qe) {
            logger.error("error while fetching calendars", qe);
        }

        return calenderRefs;
    }

    /**
     * Retrieves all editable calendars.
     *
     * @return a list of DocumentReference objects representing the editable calendars. If no editable calendars are
     *     found, an empty list is returned.
     */
    public List<DocumentReference> getAllEditableCalendars()
    {
        try {
            Query query = queryManager.createQuery(CALENDAR_BASE_QUERY, Query.HQL);
            List<String> results = query.execute();
            return filterEditableCalendars(results);
        } catch (QueryException qe) {
            logger.error("Error while fetching calendars", qe);
        }

        return Collections.emptyList();
    }

    /**
     * get a list of events matching the date and filter criteria.
     *
     * @param dateFrom the start range
     * @param dateTo the end range; can be null. in that case dates form a single day are returned
     * @param filter how to filter the event. if null or "wiki" return all events
     * @param parentReference the page reference to use for the filter. can be null if filter is null or "wiki".
     * @param sortAscending if true, sort events ascending by start date, else descending
     * @param calendars the calendars to display events from
     * @return a list of event instances matching the criteria; might be empty but never null
     * @throws QueryException if an error occurs while fetching the events
     */
    public List<EventInstance> queryEvents(Date dateFrom, Date dateTo, String filter, String parentReference,
        boolean sortAscending, List<String> calendars) throws QueryException
    {

        final XWikiContext context = xcontextProvider.get();

        if (dateTo == null) {
            dateTo = dateFrom;
        }

        QueryData simpleEvents = new QueryData();

        simpleEvents.getHql().append(BASE_QUERY_PREFIX);

        //
        // filter by date range
        //

        addDateRangeFilter(simpleEvents, dateFrom, dateTo);

        // and search only non-recurrent events
        simpleEvents.getHql().append(" and recurrent.value = 0 ");

        //
        // now filter by event location
        //

        addLocationFilter(simpleEvents, filter, parentReference);

        // Filter by calendar.
        addCalendarFilter(simpleEvents, calendars);

        // finally the ordering
        addOrderBy(sortAscending, simpleEvents);

        List<String> results = Collections.emptyList();

        try {

            logger.debug("sending query [{}] and params [{}]", simpleEvents.getHql(), simpleEvents.getQueryParams());

            Query query = queryManager.createQuery(simpleEvents.getHql().toString(), Query.HQL);

            for (Map.Entry<String, Object> param : simpleEvents.getQueryParams().entrySet()) {
                query.bindValue(param.getKey(), param.getValue());
            }

            results = query.execute();
        } catch (QueryException qe) {
            logger.error("error while fetching regular events", qe);
        }

        List<DocumentReference> visibleEvents = filterViewableEvents(results);

        List<EventInstance> events = new ArrayList<>();

        for (DocumentReference eventDocRef : visibleEvents) {
            try {
                // DocumentReference eventDocRef = stringDocRefResolver.resolve(docRef);
                XWikiDocument eventDoc = context.getWiki().getDocument(eventDocRef, context);
                BaseObject eventData = eventDoc
                    .getXObject(stringDocRefResolver.resolve(EventConstants.MOCCA_CALENDAR_EVENT_CLASS_NAME));
                if (eventData == null) {
                    logger.error("data inconsistency: query returned [{}] which contains no object for [{}]",
                        eventDocRef, EventConstants.MOCCA_CALENDAR_EVENT_CLASS_NAME);
                    continue;
                }

                EventInstance event = new EventInstance();
                event.setEventDocRef(eventDocRef);

                Date startDate = eventData.getDateValue(EventConstants.PROPERTY_STARTDATE_NAME);
                DateTime startDateTime = new DateTime(startDate.getTime());
                event.setStartDate(startDateTime);

                Date endDate = Utils.fetchOrGuessEndDate(eventData);
                DateTime endDateTime = new DateTime(endDate.getTime());
                event.setEndDate(endDateTime);

                completeEventData(event, eventDoc, eventData);

                events.add(event);
            } catch (XWikiException e) {
                logger.warn("cannot find event data [{}]", eventDocRef, e);
            }
        }

        //
        // so much for regular single events.
        // now about recurrent events
        //

        QueryData recurrentEventQuery = new QueryData();

        recurrentEventQuery.getHql().append(BASE_QUERY_PREFIX);

        //
        // filter by location
        //
        addLocationFilter(recurrentEventQuery, filter, parentReference);

        // and search only recurrent events
        recurrentEventQuery.getHql().append(" and recurrent.value = 1 ");

        try {
            List<String> allRecurrentEvents = Collections.emptyList();

            logger.debug("sending query [{}] and params [{}]", recurrentEventQuery.getHql(),
                recurrentEventQuery.getQueryParams());

            Query query = queryManager.createQuery(recurrentEventQuery.getHql().toString(), Query.HQL);

            for (Map.Entry<String, Object> param : recurrentEventQuery.getQueryParams().entrySet()) {
                query.bindValue(param.getKey(), param.getValue());
            }

            allRecurrentEvents = query.execute();

            List<DocumentReference> visibleRecurrentEvents = filterViewableEvents(allRecurrentEvents);

            List<EventInstance> recurrentEventInstances = filterRecurrentEvents(visibleRecurrentEvents,
                dateFrom, dateTo);

            events.addAll(recurrentEventInstances);

        } catch (QueryException | XWikiException e) {
            logger.error("error while fetching recurrent events", e);
        }

        sortEvents(events, sortAscending);

        return events;
    }

    /**
     * get a list of events matching the date and filter criteria.
     *
     * @param dateFrom the start range
     * @param dateTo the end range; can be null. in that case dates form a single day are returned
     * @param filter how to filter the event. if null or "wiki" return all events
     * @param parentReference the page reference to use for the filter. can be null if filter is null or "wiki".
     * @param sortAscending if true, sort events ascending by start date, else descending
     * @return a list of event instances matching the criteria; might be empty but never null
     * @throws QueryException if an error occurs while fetching the events
     */
    public List<EventInstance> queryEvents(Date dateFrom, Date dateTo, String filter, String parentReference,
        boolean sortAscending) throws QueryException
    {
        return queryEvents(dateFrom, dateTo, filter, parentReference, sortAscending, Collections.emptyList());
    }

    private List<DocumentReference> filterViewableEvents(List<String> eventDocRefs)
    {
        List<DocumentReference> visibleRefs = new ArrayList<>();
        // check view rights on results ... should use "viewable" filter when minimal platform version is >= 9.8
        final DocumentReference userReference = xcontextProvider.get().getUserReference();
        for (ListIterator<String> iter = eventDocRefs.listIterator(); iter.hasNext();) {
            DocumentReference eventDocRef = stringDocRefResolver.resolve(iter.next());
            if (authorizationManager.hasAccess(Right.VIEW, userReference, eventDocRef)) {
                visibleRefs.add(eventDocRef);
            }
        }

        return visibleRefs;
    }

    private List<DocumentReference> filterEditableCalendars(List<String> calendars)
    {
        List<DocumentReference> editableCalendars = new ArrayList<>();
        // Check edit rights on results.
        final DocumentReference userReference = xcontextProvider.get().getUserReference();
        for (String calendar : calendars) {
            DocumentReference calendarDocRef = stringDocRefResolver.resolve(calendar);
            if (authorizationManager.hasAccess(Right.EDIT, userReference, calendarDocRef)) {
                editableCalendars.add(calendarDocRef);
            }
        }

        return editableCalendars;
    }

    private List<EventInstance> filterRecurrentEvents(List<DocumentReference> eventReferences, Date dateFrom,
        Date dateTo) throws XWikiException
    {
        final XWikiContext context = xcontextProvider.get();
        final List<EventInstance> eventsInstances = new ArrayList<>();
        for (DocumentReference eventDocRef : eventReferences) {
            // DocumentReference eventDocRef = stringDocRefResolver.resolve(docRef);
            XWikiDocument eventDoc = context.getWiki().getDocument(eventDocRef, context);
            BaseObject eventData = eventDoc
                .getXObject(eventDoc.resolveClassReference(EventConstants.MOCCA_CALENDAR_EVENT_CLASS_NAME));
            BaseObject eventRecData = eventDoc.getXObject(
                eventDoc.resolveClassReference(EventConstants.MOCCA_CALENDAR_EVENT_RECURRENCY_CLASS_NAME));

            if (eventRecData == null) {
                // duh
                logger.info("found recurrent event [{}] without recurrency information; skipping",
                    eventDocRef);
                continue;
            }

            String eventType = eventRecData.getStringValue("frequency");
            RecurrentEventGenerator generator = this.eventGenerators.get(eventType);
            if (generator == null) {
                logger.error("no recurrent event generator found for frequency [{}] used by [{}]",
                    eventType, eventDocRef);
                continue;
            }

            Set<Long> deletions = deletedEventsOf(eventDoc);
            Map<Long, EventInstance> modifiedEvents = modifiedEventsOf(eventDoc, dateFrom, dateTo);

            for (EventInstance event : generator.generate(eventDoc, dateFrom, dateTo)) {
                if (deletions.contains(event.getStartDate().getMillis())) {
                    if (logger.isTraceEnabled()) {
                        logger.trace("skip deleted event at {} for doc [{}])",
                            event.getStartDate(), eventDoc);
                    }
                    continue;
                }

                EventInstance modifiedEvent = modifiedEvents.remove(event.getStartDate().getMillis());
                if (modifiedEvent != null) {
                    event = modifiedEvent;
                }

                // add extra stuff here that the generator does not have to set
                event.setRecurrent(true);

                completeEventData(event, eventDoc, eventData);
                eventsInstances.add(event);
            }

            // TODO: what happens with modified events where the original event is not
            // in our time range, but the modified one is?
            if (!modifiedEvents.isEmpty()) {
                logger.info("we dropped some modifications: [{}]", modifiedEvents.size());
                if (logger.isDebugEnabled()) {
                    for (Map.Entry<Long, EventInstance> modification : modifiedEvents.entrySet()) {
                        logger.debug("event originally started at [{}]", new DateTime(modification.getKey()));
                        EventInstance modifiedEvent = modification.getValue();
                        logger.debug("  event start at [{}]", modifiedEvent.getStartDate());
                        logger.debug("  event end at [{}]", modifiedEvent.getEndDate());
                    }
                    logger.debug("======= end of list of dropped modifications");
                }
            }
        }
        return eventsInstances;
    }

    private void completeEventData(EventInstance event, XWikiDocument eventDoc, BaseObject eventData)
        throws XWikiException
    {
        final XWikiContext context = xcontextProvider.get();
        final String defaultPageName = defaultEntityReferenceProvider.getDefaultReference(EntityType.DOCUMENT)
            .getName();
        final DocumentReference eventDocRef = eventDoc.getDocumentReference();

        boolean isAllDay = eventData.getIntValue(EventConstants.PROPERTY_ALLDAY_NAME) == 1;

        DateTime endDateExclusive = event.getEndDate();
        if (isAllDay) {
            // as end date is actually treated exclusive by the calendar
            // but inclusive by the input data:
            endDateExclusive = endDateExclusive.plusDays(1);
        }

        event.setEndDateExclusive(endDateExclusive);

        event.setAllDay(isAllDay);

        if (null == event.getTitle()) {
            event.setTitle(eventDoc.getRenderedTitle(Syntax.PLAIN_1_0, context));
        }

        if (null == event.getDescription()) {
            Utils.fillDescription(eventData, context, event);
        }

        event.setEventDocRef(eventDocRef);

        /* the corresponding calendar page should be the default page of the parent space.
         * this is the space of the page if the event page is terminal, and the parent
         * of the events page space, if the page is non-terminal
         */
        BaseObject calendarData = null;
        SpaceReference parentSpaceRef = null;
        if (defaultPageName.equals(eventDocRef.getName())) {
            EntityReference parentRef = eventDocRef.getLastSpaceReference().getParent();
            if ((parentRef != null) && (parentRef instanceof SpaceReference)) {
                parentSpaceRef = (SpaceReference) parentRef;
            }
        } else {
            parentSpaceRef = eventDocRef.getLastSpaceReference();
        }

        if (parentSpaceRef != null) {
            DocumentReference parentDoc = new DocumentReference(defaultPageName, parentSpaceRef);
            XWikiDocument calendarDoc = context.getWiki().getDocument(parentDoc, context);
            calendarData = calendarDoc
                .getXObject(stringDocRefResolver.resolve(EventConstants.MOCCA_CALENDAR_CLASS_NAME));
        }

        if (calendarData == null) {
            // some arbitrary defaults
            event.setBackgroundColor("#888");
            // text color can be missing
            event.setTextColor("");
        } else {
            event.setTextColor(calendarData.getStringValue("textColor"));
            event.setBackgroundColor(calendarData.getStringValue("color"));
        }

    }

    /**
     * find modification data for an event instance, if the event instance has been modified.
     * @param eventDoc the document of the recurrent event
     * @param eventStartDate the original start date of the event instance
     * @return the index of a MoccaCalendarEventModificationClass object for the event instance,
     *    or -1 if no modification has been found for the event instance
     */
    public int getModifiedEventObjectIndex(Document eventDoc, Date eventStartDate)
    {
        final List<BaseObject> modificationNotices = eventDoc.getDocument().
            getXObjects(stringDocRefResolver.resolve(EventConstants.MOCCA_CALENDAR_EVENT_MODIFICATION_CLASS_NAME));
        if (modificationNotices != null) {
            for (int i = 0, n = modificationNotices.size(); i < n; i++) {
                BaseObject modificationNotice = modificationNotices.get(i);
                Date modificationDate = (modificationNotice == null)
                    ? null : modificationNotice.getDateValue(EventConstants.PROPERTY_ORIG_STARTDATE_OF_MODIFIED_NAME);
                if (eventStartDate.equals(modificationDate)) {
                    return i;
                }
            }
        }
        return -1;
    }

    /**
     * create a dummy modification object to be used as placeholder in edit view.
     * @param eventDoc the document containing the (recurrent) event to be modified.
     * @param eventStartDate the original start date of the unmodified event instance
     * @return a non-persistent event modification object containing default values
     */
    public com.xpn.xwiki.api.Object createModificationDummy(Document eventDoc, Date eventStartDate)
    {
        final XWikiDocument xwikiEventDoc = eventDoc.getDocument();
        final BaseObject eventData = xwikiEventDoc.getXObject(
            stringDocRefResolver.resolve(EventConstants.MOCCA_CALENDAR_EVENT_CLASS_NAME));

        BaseObject modificationData = new BaseObject();
        modificationData.setXClassReference(
            stringDocRefResolver.resolve(EventConstants.MOCCA_CALENDAR_EVENT_MODIFICATION_CLASS_NAME));
        modificationData.setOwnerDocument(xwikiEventDoc);
        modificationData.setNumber(-1);

        if (eventData != null) {
            Date defaultStartDate = (eventStartDate != null)
                ? eventStartDate : eventData.getDateValue(EventConstants.PROPERTY_STARTDATE_NAME);
            modificationData.setDateValue(EventConstants.PROPERTY_STARTDATE_NAME, defaultStartDate);

            final Date baseStartDate = eventData.getDateValue(EventConstants.PROPERTY_STARTDATE_NAME);
            final Date baseEndDate = Utils.fetchOrGuessEndDate(eventData);
            final long baseDuration = baseEndDate.getTime() - baseStartDate.getTime();

            Date defaultEndDate = new Date(defaultStartDate.getTime() + baseDuration);
            modificationData.setDateValue(EventConstants.PROPERTY_ENDDATE_NAME, defaultEndDate);

            modificationData.setLargeStringValue(EventConstants.PROPERTY_DESCRIPTION_NAME,
                eventData.getLargeStringValue(EventConstants.PROPERTY_DESCRIPTION_NAME));

            modificationData.setStringValue(EventConstants.PROPERTY_TITLE_NAME,
                xwikiEventDoc.getTitle());
        }

        return new com.xpn.xwiki.api.Object(modificationData, xcontextProvider.get());
    }

    /**
     * create an event instance for the given date and document.
     * if the event instance has been modified, update the event instance with the modifications.
     * this methods does not take deletion marks into account, but always returns an event instance.
     * @param eventDoc the document of the recurrent event
     * @param eventStartDate the original start date of the event instance (might be null for the unaltered event)
     * @return the EventInstance with the (possibly modified) values of the event
     */
    public EventInstance getEventInstance(final Document eventDoc, final Date eventStartDate)
    {
        final XWikiDocument xwikiEventDoc = eventDoc.getDocument();
        final BaseObject eventData = xwikiEventDoc.
            getXObject(stringDocRefResolver.resolve(EventConstants.MOCCA_CALENDAR_EVENT_CLASS_NAME));
        EventInstance event = null;
        if (eventData == null) {
            return event;
        }

        int objIndex;
        Date originalEventStartDate;
        if (eventStartDate == null)
        {
            originalEventStartDate = eventData.getDateValue(EventConstants.PROPERTY_STARTDATE_NAME);
            objIndex = -1;
        } else {
            originalEventStartDate = eventStartDate;
            objIndex = getModifiedEventObjectIndex(eventDoc, eventStartDate);
        }

        BaseObject modificationData = null;
        if (objIndex != -1) {
            modificationData = xwikiEventDoc.
                getXObject(stringDocRefResolver.resolve(
                    EventConstants.MOCCA_CALENDAR_EVENT_MODIFICATION_CLASS_NAME), objIndex);
        } else {
            // we create a dummy which always returns null for all properties
            modificationData = new BaseObject();
        }
        event = createModifiedEventData(xwikiEventDoc, eventData, modificationData, originalEventStartDate, null, null);

        try {
            completeEventData(event, xwikiEventDoc, eventData);
        } catch (XWikiException xe) {
            logger.info("could not create modified event data for document [{}] and date [{}]",
                eventDoc, eventStartDate, xe);
        }

        return event;
    }

    //
    // these helpers probably should be "QueryData" methods
    //

    private void addDateRangeFilter(QueryData data, Date dateFrom, Date dateTo)
    {
        // start date / lower limit check: find all events which are not finished before the start date
        // for this, confusingly, one need to compare the end date of the event with the start date for the range
        // as a complication: to find events without end date, use the start date for them
        data.getHql().append("and (enddate.value is not null and ");
        appendDateCriterion(data, "enddate.value", "start", true);
        data.getHql().append(" or ");
        appendDateCriterion(data, "startdate.value", "start", true);
        data.getHql().append(')');

        // compared to this the upper limit check is straightforward, as we always have a startDate in the event
        data.getHql().append(" and ");
        appendDateCriterion(data, "startdate.value", "end", false);

        appendDateParameters(data, "start", dateFrom);
        appendDateParameters(data, "end", dateTo);
    }

    private void addLocationFilter(QueryData data, String filter, String parentReference)
    {
        switch (filter) {
            case FILTER_PAGE:
                DocumentReference parentRef = stringDocRefResolver.resolve(parentReference);
                data.getHql().insert(0, ", XWikiSpace space");
                data.getHql().append(" and doc.space = space.reference and space.parent = :space");
                data.getQueryParams().put("space", compactWikiSerializer.serialize(parentRef.getLastSpaceReference()));
                break;
            case FILTER_SPACE:
                parentRef = stringDocRefResolver.resolve(parentReference);
                // XXX maybe use the "bindValue(...).literal(...) instead?
                data.getHql().append(" and ( doc.space like :space escape '!')");
                String spaceRefStr = compactWikiSerializer.serialize(parentRef.getLastSpaceReference());
                String spaceLikeStr = spaceRefStr.replaceAll("([%_!])", "!$1").concat(".%");
                data.getQueryParams().put("space", spaceLikeStr);
                break;
            case FILTER_WIKI:
            default:
                // get events from the complete wiki: no filter to be added
                break;
        }
    }


    private void addCalendarFilter(QueryData data, List<String> calendars)
    {
        if (!calendars.isEmpty()) {
            data.getHql().append(" and (");
            int index = 0;
            for (String calendar : calendars) {
                String spaceParamName = String.format("space%s", index);
                if (index > 0) {
                    data.getHql().append(" or ");
                }
                data.getHql().append(String.format("doc.space like :%s", spaceParamName));
                DocumentReference calendarRef = stringDocRefResolver.resolve(calendar);
                String spaceRefStr = compactWikiSerializer.serialize(calendarRef.getLastSpaceReference());
                String spaceLikeStr = spaceRefStr.concat(".%");
                data.getQueryParams().put(spaceParamName, spaceLikeStr);
                index ++;
            }
            data.getHql().append(")");
        }
    }

    private void addOrderBy(boolean sortAscending, QueryData simpleEvents)
    {
        simpleEvents.getHql().append(" order by startdate.value ");
        if (sortAscending) {
            simpleEvents.getHql().append("asc");
        } else {
            simpleEvents.getHql().append("desc");
        }
    }

    // the date comparision in HQL is always a bit painful - hide it in a helper
    // for appendDateCriterion(query, "date", "field", true) this will create something like:
    //
    // ( year(date) > :fieldyear or ( year(date) = :fieldyear and ( month(date) > :fieldmonth or
    // ( month(date) = :fieldmonth and day(date) >= :fieldday ) ) ) )
    // ...
    private static void appendDateCriterion(QueryData data, String dateField, String prefix, boolean larger)
    {
        final char cmpSign = (larger) ? '>' : '<';

        data.getHql().append("( year(" + dateField + ") ").append(cmpSign).append(" :" + prefix + "year ");
        data.getHql().append(" or (year(" + dateField + ") = :" + prefix + "year and ");
        data.getHql().append("(month(" + dateField + ") ").append(cmpSign).append(" :" + prefix + "month");
        data.getHql().append(" or (month(" + dateField + ") = :" + prefix + "month ");
        data.getHql().append(" and day(").append(dateField).append(") ").append(cmpSign).append("= :")
            .append(prefix).append("day");
        data.getHql().append(')');
        data.getHql().append(')');
        data.getHql().append(')');
        data.getHql().append(')');
    }

    private void appendDateParameters(QueryData data, String prefix, Date date)
    {
        Calendar cal = Calendar.getInstance();
        cal.setTime(date);

        int year = cal.get(Calendar.YEAR);
        int month = cal.get(Calendar.MONTH) + 1;
        int day = cal.get(Calendar.DAY_OF_MONTH);
        data.getQueryParams().put(prefix + "year", year);
        data.getQueryParams().put(prefix + "month", month);
        data.getQueryParams().put(prefix + "day", day);
    }

    private void sortEvents(final List<EventInstance> events, final boolean ascending)
    {
        Collections.sort(events, new Comparator<EventInstance>()
        {
            @Override
            public int compare(EventInstance event1, EventInstance event2)
            {
                int result;
                final DateTime startDate1 = event1.getStartDate();
                final DateTime startDate2 = event2.getStartDate();
                if (startDate1 == null) {
                    result = (startDate2 == null) ? 0 : -1;
                } else if (startDate2 == null) {
                    result = 1;
                } else {
                    result = startDate1.compareTo(startDate2);
                }
                return (ascending) ? result : -result;
            }

        });
    }

    private Set<Long> deletedEventsOf(XWikiDocument eventDoc)
    {
        Set<Long> deletions = new HashSet<>();

        final List<BaseObject> deleteNotices = eventDoc.
            getXObjects(stringDocRefResolver.resolve(EventConstants.MOCCA_CALENDAR_EVENT_DELETION_CLASS_NAME));
        if (deleteNotices != null) {
            for (BaseObject deleteNotice : deleteNotices) {
                Date deleted = (deleteNotice == null)
                    ? null : deleteNotice.getDateValue(EventConstants.PROPERTY_STARTDATE_OF_DELETED_NAME);
                if (deleted != null) {
                    deletions.add(deleted.getTime());
                }
            }
        }

        if (!deletions.isEmpty() && logger.isDebugEnabled()) {
            logger.debug("found {} deletions for event [{}])", deletions.size(), eventDoc);
        }

        return deletions;
    }

    /**
     * find all modified events for an event document within a given time frame.
     * @param eventDoc the document of the recurrent event
     * @param dateFrom the date from which the events are sought
     * @param dateTo the date up to which the events are sought
     * @return a map of original event dates to instances filled with the corresponding modifications
     */
    private Map<Long, EventInstance> modifiedEventsOf(XWikiDocument eventDoc, Date dateFrom, Date dateTo)
    {
        final Map<Long, EventInstance> results = new HashMap<>();
        final List<BaseObject> modificationNotices = eventDoc
                .getXObjects(stringDocRefResolver.resolve(EventConstants.MOCCA_CALENDAR_EVENT_MODIFICATION_CLASS_NAME));
        final BaseObject eventData = eventDoc
                .getXObject(stringDocRefResolver.resolve(EventConstants.MOCCA_CALENDAR_EVENT_CLASS_NAME));

        if (modificationNotices != null) {
            for (int i = 0, n = modificationNotices.size(); i < n; i++) {

                BaseObject modificationNotice = modificationNotices.get(i);
                if (modificationNotice == null) {
                    continue;
                }

                Date originalStartDate = modificationNotice.getDateValue(
                    EventConstants.PROPERTY_ORIG_STARTDATE_OF_MODIFIED_NAME);
                if (originalStartDate == null) {
                    continue;
                }

                EventInstance modifiedInstance = createModifiedEventData(eventDoc, eventData, modificationNotice,
                    originalStartDate, dateFrom, dateTo);
                if (modifiedInstance == null) {
                    continue;
                }

                results.put(originalStartDate.getTime(), modifiedInstance);
            }
        }
        return results;
    }

    /**
     * Helper to create an event instance from modification data.
     * The very long parameter list is necessary as the code is called from several places.
     * If the modified event is not in the given date range, this helper returns a null.
     * The original start date is not optional, as we cannot guess it from the data if the modificationNotice is a dummy
     * The date ranges are optional, if they are null, no check for the date range is done.
     * @param eventDoc the document containing the event
     * @param eventData the main even data
     * @param modificationNotice the object containing the modification
     * @param originalStartDate the original start date of the event, must not be null
     * @param dateFrom the start of the date range, can be null
     * @param dateTo the end of the date range can be null
     * @return the event instance with (only) the modified data filled in
     */
    private EventInstance createModifiedEventData(XWikiDocument eventDoc,
        BaseObject eventData, BaseObject modificationNotice,
        Date originalStartDate, Date dateFrom, Date dateTo)
    {
        final Date baseStartDate = eventData.getDateValue(EventConstants.PROPERTY_STARTDATE_NAME);
        final Date baseEndDate = Utils.fetchOrGuessEndDate(eventData);
        final long baseDuration = baseEndDate.getTime() - baseStartDate.getTime();

        // now get both the original start / end date
        // and the modified start / end date, and add a rudimentary event instance to result,
        // unless:
        //  a) both the original and new end date are before the "dateFrom"
        // or
        //  b) both the original start date or the modified start date are after the "dateTo"

        Date originalEndDate = new Date(originalStartDate.getTime() + baseDuration);
        Date actualStartDate = modificationNotice.getDateValue(EventConstants.PROPERTY_STARTDATE_NAME);
        if (actualStartDate == null) {
            actualStartDate = originalStartDate;
        }
        // the following does not work if we have a modification without start date:
        // Date actualEndDate = Utils.fetchOrGuessEndDate(modificationNotice);
        // so instead:
        Date actualEndDate = modificationNotice.getDateValue(EventConstants.PROPERTY_ENDDATE_NAME);
        if (actualEndDate == null) {
            // we need to calculate the actual end date from the actual start date, but only if this has been defined
            // otherwise if we have no modified start date and no modified end date given,
            // then the end date is the same as the original end date
            // XXX: what if the "allDay" flag is changed on the event? currently this is not supported
            if (actualStartDate.equals(originalStartDate)) {
                actualEndDate = originalEndDate;
            } else {
                final boolean allDay = eventData.getIntValue(EventConstants.PROPERTY_ALLDAY_NAME) == 1;
                actualEndDate = Utils.guessEndDate(actualStartDate, allDay);
            }
        }

        // now we can figure out if the modified event is in the right time frame
        if (dateFrom != null && actualEndDate.before(dateFrom) && originalEndDate.before(dateFrom)) {
            return null;
        }
        if (dateTo != null && actualStartDate.after(dateTo) && originalStartDate.after(dateTo)) {
            return null;
        }

        EventInstance modifiedInstance = new EventInstance();
        modifiedInstance.setStartDate(new DateTime(actualStartDate.getTime()));
        modifiedInstance.setOriginalStartDate(new DateTime(originalStartDate.getTime()));
        modifiedInstance.setEndDate(new DateTime(actualEndDate.getTime()));

        XWikiContext context = xcontextProvider.get();
        String modifiedTitle = modificationNotice.getStringValue(EventConstants.PROPERTY_TITLE_NAME);
        if (modifiedTitle != null && !"".equals(modifiedTitle.trim())) {
            modifiedInstance.setTitle(eventDoc.getRenderedContent(modifiedTitle, eventDoc.getSyntax().toIdString(),
                    Syntax.PLAIN_1_0.toIdString(), context));
        }
        String modifiedDescription = modificationNotice.getStringValue(EventConstants.PROPERTY_DESCRIPTION_NAME);
        if (modifiedDescription != null && !"".equals(modifiedDescription.trim())) {
            Utils.fillDescription(modificationNotice, context, modifiedInstance);
        }

        return modifiedInstance;
    }

}
