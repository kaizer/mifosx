/**
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.mifosplatform.portfolio.calendar.api;

import org.mifosplatform.commands.domain.CommandWrapper;
import org.mifosplatform.commands.service.CommandWrapperBuilder;
import org.mifosplatform.commands.service.PortfolioCommandSourceWritePlatformService;
import org.mifosplatform.infrastructure.core.api.ApiParameterHelper;
import org.mifosplatform.infrastructure.core.api.ApiRequestParameterHelper;
import org.mifosplatform.infrastructure.core.data.CommandProcessingResult;
import org.mifosplatform.infrastructure.core.data.EnumOptionData;
import org.mifosplatform.infrastructure.core.serialization.ApiRequestJsonSerializationSettings;
import org.mifosplatform.infrastructure.core.serialization.DefaultToApiJsonSerializer;
import org.mifosplatform.infrastructure.security.service.PlatformSecurityContext;
import org.mifosplatform.portfolio.calendar.data.CalendarData;
import org.mifosplatform.portfolio.calendar.domain.Calendar;
import org.mifosplatform.portfolio.calendar.domain.CalendarEntityType;
import org.mifosplatform.portfolio.calendar.domain.CalendarType;
import org.mifosplatform.portfolio.calendar.service.CalendarDropdownReadPlatformService;
import org.mifosplatform.portfolio.calendar.service.CalendarEnumerations;
import org.mifosplatform.portfolio.calendar.service.CalendarReadPlatformService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import javax.ws.rs.*;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.UriInfo;
import java.util.*;

@Path("/{entityType}/{entityId}/calendars")
@Component
@Scope("singleton")
public class CalendarsApiResource {

    /**
     * The set of parameters that are supported in response for {@link Calendar}
     */
    private final Set<String> RESPONSE_DATA_PARAMETERS = new HashSet<String>(Arrays.asList("id", "entityId", "entityType", "title",
            "description", "location", "startDate", "endDate", "duration", "type", "repeating", "recurrence", "remindBy", "firstReminder",
            "secondReminder", "humanReadable", "createdDate", "lastUpdatedDate", "createdByUserId", "createdByUsername",
            "lastUpdatedByUserId", "lastUpdatedByUsername", "recurringDates","nextTenRecurringDates"));
    private final String resourceNameForPermissions = "CALENDAR";

    private final PlatformSecurityContext context;
    private final CalendarReadPlatformService readPlatformService;
    private final DefaultToApiJsonSerializer<CalendarData> toApiJsonSerializer;
    private final ApiRequestParameterHelper apiRequestParameterHelper;
    private final PortfolioCommandSourceWritePlatformService commandsSourceWritePlatformService;
    private final CalendarDropdownReadPlatformService dropdownReadPlatformService;

    @Autowired
    public CalendarsApiResource(final PlatformSecurityContext context, final CalendarReadPlatformService readPlatformService,
            final DefaultToApiJsonSerializer<CalendarData> toApiJsonSerializer, final ApiRequestParameterHelper apiRequestParameterHelper,
            final PortfolioCommandSourceWritePlatformService commandsSourceWritePlatformService,
            final CalendarDropdownReadPlatformService dropdownReadPlatformService) {
        this.context = context;
        this.readPlatformService = readPlatformService;
        this.toApiJsonSerializer = toApiJsonSerializer;
        this.apiRequestParameterHelper = apiRequestParameterHelper;
        this.commandsSourceWritePlatformService = commandsSourceWritePlatformService;
        this.dropdownReadPlatformService = dropdownReadPlatformService;
    }

    @GET
    @Path("{calendarId}")
    @Consumes({ MediaType.APPLICATION_JSON })
    @Produces({ MediaType.APPLICATION_JSON })
    public String retrieveCalendar(@PathParam("calendarId") final Long calendarId, @PathParam("entityType") final String entityType,
            @PathParam("entityId") final Long entityId, @Context final UriInfo uriInfo) {

        this.context.authenticatedUser().validateHasReadPermission(this.resourceNameForPermissions);
        final Integer entityTypeId = CalendarEntityType.valueOf(entityType.toUpperCase()).getValue();
        CalendarData calendarData = this.readPlatformService.retrieveCalendar(calendarId, entityId, entityTypeId);

        // Include recurring date details
        calendarData = this.readPlatformService.generateRecurringDate(calendarData);

        final ApiRequestJsonSerializationSettings settings = this.apiRequestParameterHelper.process(uriInfo.getQueryParameters());
        if (settings.isTemplate()) {
            calendarData = handleTemplate(calendarData);
        }
        return this.toApiJsonSerializer.serialize(settings, calendarData, this.RESPONSE_DATA_PARAMETERS);
    }

    @GET
    @Consumes({ MediaType.APPLICATION_JSON })
    @Produces({ MediaType.APPLICATION_JSON })
    public String retrieveCalendarsByEntity(@PathParam("entityType") final String entityType, @PathParam("entityId") final Long entityId,
                                            @Context final UriInfo uriInfo,@DefaultValue("all") @QueryParam("calendarType") String calendarType) {

        this.context.authenticatedUser().validateHasReadPermission(this.resourceNameForPermissions);

        final Set<String> associationParameters = ApiParameterHelper.extractAssociationsForResponseIfProvided(uriInfo.getQueryParameters());

        Collection<CalendarData> calendarsData = new ArrayList<CalendarData>();

        List<EnumOptionData>  calendarTypeOptions = createEnumOptionDataListFromQueryParameter(calendarType);


        if (!associationParameters.isEmpty()) {
            if (associationParameters.contains("parentCalendars")) {
                calendarsData.addAll(this.readPlatformService.retrieveParentCalendarsByEntity(entityId,
                        CalendarEntityType.valueOf(entityType.toUpperCase()).getValue(),calendarTypeOptions));
            }
        }

        calendarsData.addAll(this.readPlatformService.retrieveCalendarsByEntity(entityId,
                CalendarEntityType.valueOf(entityType.toUpperCase()).getValue(),calendarTypeOptions));

        // Add recurring dates
        calendarsData = this.readPlatformService.generateRecurringDates(calendarsData);

        final ApiRequestJsonSerializationSettings settings = this.apiRequestParameterHelper.process(uriInfo.getQueryParameters());
        return this.toApiJsonSerializer.serialize(settings, calendarsData, this.RESPONSE_DATA_PARAMETERS);
    }

    @GET
    @Path("template")
    @Consumes({ MediaType.APPLICATION_JSON })
    @Produces({ MediaType.APPLICATION_JSON })
    public String retrieveNewCalendarDetails(@Context final UriInfo uriInfo) {

        this.context.authenticatedUser().validateHasReadPermission(this.resourceNameForPermissions);

        CalendarData calendarData = this.readPlatformService.retrieveNewCalendarDetails();
        calendarData = handleTemplate(calendarData);

        final ApiRequestJsonSerializationSettings settings = this.apiRequestParameterHelper.process(uriInfo.getQueryParameters());
        return this.toApiJsonSerializer.serialize(settings, calendarData, this.RESPONSE_DATA_PARAMETERS);
    }

    @POST
    @Consumes({ MediaType.APPLICATION_JSON })
    @Produces({ MediaType.APPLICATION_JSON })
    public String createCalendar(@PathParam("entityType") final String entityType, @PathParam("entityId") final Long entityId,
            final String apiRequestBodyAsJson) {

        final CommandWrapper commandRequest = new CommandWrapperBuilder().createCalendar(entityType, entityId)
                .withJson(apiRequestBodyAsJson).build();

        final CommandProcessingResult result = this.commandsSourceWritePlatformService.logCommandSource(commandRequest);

        return this.toApiJsonSerializer.serialize(result);

    }

    @PUT
    @Path("{calendarId}")
    @Consumes({ MediaType.APPLICATION_JSON })
    @Produces({ MediaType.APPLICATION_JSON })
    public String updateCalendar(@PathParam("entityType") final String entityType, @PathParam("entityId") final Long entityId,
            @PathParam("calendarId") final Long calendarId, final String jsonRequestBody) {

        final CommandWrapper commandRequest = new CommandWrapperBuilder().updateCalendar(entityType, entityId, calendarId)
                .withJson(jsonRequestBody).build();

        final CommandProcessingResult result = this.commandsSourceWritePlatformService.logCommandSource(commandRequest);

        return this.toApiJsonSerializer.serialize(result);
    }

    @DELETE
    @Path("{calendarId}")
    @Consumes({ MediaType.APPLICATION_JSON })
    @Produces({ MediaType.APPLICATION_JSON })
    public String deleteGLAccount(@PathParam("entityType") final String entityType, @PathParam("entityId") final Long entityId,
            @PathParam("calendarId") final Long calendarId) {

        final CommandWrapper commandRequest = new CommandWrapperBuilder().deleteCalendar(entityType, entityId, calendarId).build();

        final CommandProcessingResult result = this.commandsSourceWritePlatformService.logCommandSource(commandRequest);

        return this.toApiJsonSerializer.serialize(result);
    }

    private CalendarData handleTemplate(final CalendarData calendarData) {
        final List<EnumOptionData> entityTypeOptions = this.dropdownReadPlatformService.retrieveCalendarEntityTypeOptions();
        final List<EnumOptionData> calendarTypeOptions = this.dropdownReadPlatformService.retrieveCalendarTypeOptions();
        final List<EnumOptionData> remindByOptions = this.dropdownReadPlatformService.retrieveCalendarRemindByOptions();
        return new CalendarData(calendarData, entityTypeOptions, calendarTypeOptions, remindByOptions);
    }


    public List<EnumOptionData> createEnumOptionDataListFromQueryParameter(String calendarTypeQuery) {
        List<EnumOptionData> calendarTypeOptions = new ArrayList<EnumOptionData>();
        // adding all calendar Types if
        if(calendarTypeQuery.equalsIgnoreCase("all")){
            return calendarTypeOptions;
        }
        // creating a list of calendar type options from the comma separated query parameter.
        List<String> calendarTypeOptionsInQuery = new ArrayList<String>();
        StringTokenizer st = new StringTokenizer(calendarTypeQuery, ",");
        while (st.hasMoreElements()) {
            calendarTypeOptionsInQuery.add(st.nextElement().toString());
        }

        for(String calType : calendarTypeOptionsInQuery){
            if(calType.equalsIgnoreCase("collection")) {
                calendarTypeOptions.add(CalendarEnumerations.calendarType(CalendarType.COLLECTION));
            } else if(calType.equalsIgnoreCase("training")) {
                calendarTypeOptions.add(CalendarEnumerations.calendarType(CalendarType.TRAINING));
            }  else if(calType.equalsIgnoreCase("audit")) {
                calendarTypeOptions.add(CalendarEnumerations.calendarType(CalendarType.AUDIT));
            }  else if(calType.equalsIgnoreCase("general")) {
                calendarTypeOptions.add(CalendarEnumerations.calendarType(CalendarType.GENERAL));
            }
        }

        return calendarTypeOptions;
    }

}