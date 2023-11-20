package org.sunbird.insights.controller.service;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.sunbird.cache.RedisCacheMgr;
import org.sunbird.cassandra.utils.CassandraOperation;
import org.sunbird.common.model.SBApiResponse;
import org.sunbird.common.util.CbExtServerProperties;
import org.sunbird.common.util.ProjectUtil;
import org.sunbird.core.config.PropertiesConfig;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.*;

import static org.sunbird.common.util.Constants.*;
@Service
public class InsightsServiceImpl implements InsightsService {

    @Autowired
    CbExtServerProperties serverProperties;

    @Autowired
    private RedisCacheMgr redisCacheMgr;

    @Autowired
    CassandraOperation cassandraOperation;

    @Autowired
    PropertiesConfig propertiesConfig;

    public SBApiResponse insights(Map<String, Object> requestBody,String userId) throws Exception {
        String [] labelsCertificates = {propertiesConfig.getInsightsLabelCertificatesYourDepartment(),propertiesConfig.getInsightsLabelCertificatesAcross()} ;
        String [] labelsLearningHours = {propertiesConfig.getInsightsLabelLearningHoursYourDepartment(),propertiesConfig.getInsightsLabelLearningHoursAcross()} ;

        HashMap<String, Object> request = (HashMap<String, Object>) requestBody.get(REQUEST);
        HashMap<String, Object> filter = ((HashMap<String, Object>) request.get(FILTERS));
        ArrayList<String> organizations = (ArrayList<String>) (filter.get(ORGANISATIONS));
        ArrayList<String> keys = nudgeKeys(organizations);
        String[] fieldsArray = keys.toArray(new String[keys.size()]);
        ArrayList<Object> nudges = new ArrayList<>();
        List<String> lhpLearningHours =  redisCacheMgr.hget(INSIGHTS_LEARNING_HOURS_REDIS_KEY, serverProperties.getRedisInsightIndex(),fieldsArray);
        List<String> lhpCertifications = redisCacheMgr.hget(INSIGHTS_CERTIFICATIONS_REDIS_KEY, serverProperties.getRedisInsightIndex(),fieldsArray);
        populateIfNudgeExist(lhpLearningHours, nudges, INSIGHTS_TYPE_LEARNING_HOURS,organizations,labelsLearningHours);
        populateIfNudgeExist(lhpCertifications, nudges, INSIGHTS_TYPE_CERTIFICATE,organizations,labelsCertificates);
        HashMap<String, Object> responseMap = new HashMap<>();
        responseMap.put(WEEKLY_CLAPS, populateIfClapsExist(userId).get(0) );
        responseMap.put(NUDGES, nudges);
        SBApiResponse response = ProjectUtil.createDefaultResponse(API_USER_INSIGHTS);
        response.getResult().put(RESPONSE, responseMap);
        return response;
    }
    private ArrayList<String> nudgeKeys(ArrayList<String> organizations ){
        ArrayList<String> keys = new ArrayList<>();
        for (String org : organizations) {
           // keys.add(org + COLON + LABEL);
            keys.add(org + COLON + YESTERDAY);
            keys.add(org + COLON + TODAY);
        }
        return  keys;
    }
    private List<Map<String, Object>> populateIfClapsExist(String userId) {
        Map<String, Object> userRequest = new HashMap<>();
        userRequest.put(LEARNER_STATUS_USER_ID, userId);
        List<String> fields = new ArrayList<>();
        fields.add(LEARNER_STATUS_USER_ID);
        fields.add(TOTAL_CLAPS);
        fields.add(W1);
        fields.add(W2);
        fields.add(W3);
        fields.add(W4);
        List<Map<String, Object>>  result=  cassandraOperation.getRecordsByProperties(KEYSPACE_SUNBIRD,
                LEARNER_STATS, userRequest, fields);
        LocalDate[]  dates = populateDate();
        if(result != null & result.size() > 0) {
            result.get(0).put("startDate", dates[0]);
            result.get(0).put("endDate", dates[1]);
        }
        return result;
    }
    public void populateIfNudgeExist(List<String> data, ArrayList<Object> nudges, String type, List<String> organizations,String labels[]) {
        for (int i = 0, j = 0; i < data.size(); i += 2, j++) {
           // String label = data.get(i);
            double yesterday = StringUtils.isNotBlank(data.get(i + 1)) ? Double.parseDouble(data.get(i + 1)) : 0.0;
            double today = StringUtils.isNotBlank(data.get(i + 2)) ? Double.parseDouble(data.get(i + 2)) : 0.0;
            double change;
            if (yesterday != 0.0 || today != 0.0) {
                if (yesterday != 0.0) {
                    change = ((today - yesterday) / Math.abs(yesterday)) * 100;
                } else {
                    change = 100.0;
                }
            } else {
                change = 0.0;
            }
            HashMap<String, Object> nudge = new HashMap<>();
            nudge.put(PROGRESS, change);
            nudge.put(GROWTH, change > 0 ? POSITIVE : NEGATIVE);
            nudge.put(LABEL, organizations.get(j).equals("across") ?labels[1].replace("{0}",String.valueOf(today)) :labels[0].replace("{0}",String.valueOf(today)));
            nudge.put(ORG, organizations.get(j));
            nudge.put(TYPE, type);
            nudges.add(nudge);
        }
    }

    public LocalDate[] populateDate(){
        LocalDate currentDate = LocalDate.now();
        LocalDate endDate = currentDate.with(TemporalAdjusters.next(DayOfWeek.SUNDAY));
        LocalDate startDate = endDate.minusWeeks(4).plusDays(1);
        LocalDate[] local = new LocalDate[2];
        local[0] = startDate;
        local[1] = endDate;
        return local;
    }
}
