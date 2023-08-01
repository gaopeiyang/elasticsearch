/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.esql.action;

import org.elasticsearch.common.io.Streams;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.core.TimeValue;
import org.elasticsearch.index.mapper.DateFieldMapper;
import org.elasticsearch.index.query.QueryBuilder;
import org.elasticsearch.index.query.RangeQueryBuilder;
import org.elasticsearch.index.query.TermQueryBuilder;
import org.elasticsearch.search.SearchModule;
import org.elasticsearch.tasks.Task;
import org.elasticsearch.tasks.TaskId;
import org.elasticsearch.tasks.TaskInfo;
import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.xcontent.NamedXContentRegistry;
import org.elasticsearch.xcontent.XContentParser;
import org.elasticsearch.xcontent.XContentParserConfiguration;
import org.elasticsearch.xcontent.XContentType;

import java.io.IOException;
import java.time.ZoneId;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;

public class EsqlQueryRequestTests extends ESTestCase {

    public void testParseFields() throws IOException {
        String query = randomAlphaOfLengthBetween(1, 100);
        boolean columnar = randomBoolean();
        ZoneId zoneId = randomZone();
        Locale locale = randomLocale(random());
        QueryBuilder filter = randomQueryBuilder();
        List<Object> params = randomList(5, () -> randomBoolean() ? randomInt(100) : randomAlphaOfLength(10));
        StringBuilder paramsString = new StringBuilder();
        paramsString.append("[");
        boolean first = true;
        for (Object param : params) {
            if (first == false) {
                paramsString.append(", ");
            }
            first = false;
            if (param instanceof String) {
                paramsString.append("\"");
                paramsString.append(param);
                paramsString.append("\"");
            } else {
                paramsString.append(param);
            }
        }
        paramsString.append("]");
        String json = String.format(Locale.ROOT, """
            {
                "query": "%s",
                "columnar": %s,
                "time_zone": "%s",
                "locale": "%s",
                "filter": %s,
                "params": %s
            }""", query, columnar, zoneId, randomBoolean() ? locale.toString() : locale.toLanguageTag(), filter, paramsString);

        EsqlQueryRequest request = parseEsqlQueryRequest(json);

        assertEquals(query, request.query());
        assertEquals(columnar, request.columnar());
        assertEquals(zoneId, request.zoneId());
        assertEquals(locale, request.locale());
        assertEquals(filter, request.filter());

        assertEquals(params.size(), request.params().size());
        for (int i = 0; i < params.size(); i++) {
            assertEquals(params.get(i), request.params().get(i).value);
        }
    }

    public void testRejectUnknownFields() {
        assertParserErrorMessage("""
            {
                "query": "foo",
                "time_z0ne": "Z"
            }""", "unknown field [time_z0ne] did you mean [time_zone]?");

        assertParserErrorMessage("""
            {
                "query": "foo",
                "asdf": "Z"
            }""", "unknown field [asdf]");
    }

    public void testMissingQueryIsNotValidation() throws IOException {
        EsqlQueryRequest request = parseEsqlQueryRequest("""
            {
                "time_zone": "Z"
            }""");
        assertNotNull(request.validate());
        assertThat(request.validate().getMessage(), containsString("[query] is required"));
    }

    public void testTask() throws IOException {
        String query = randomAlphaOfLength(10);
        int id = randomInt();

        EsqlQueryRequest request = parseEsqlQueryRequest("""
            {
                "query": "QUERY"
            }""".replace("QUERY", query));
        Task task = request.createTask(id, "transport", EsqlQueryAction.NAME, TaskId.EMPTY_TASK_ID, Map.of());
        assertThat(task.getDescription(), equalTo(query));

        String localNode = randomAlphaOfLength(2);
        TaskInfo taskInfo = task.taskInfo(localNode, true);
        String json = taskInfo.toString();
        String expected = Streams.readFully(getClass().getClassLoader().getResourceAsStream("query_task.json")).utf8ToString();
        expected = expected.replaceAll("\s*<\\d+>", "")
            .replaceAll("FROM test \\| STATS MAX\\(d\\) by a, b", query)
            .replaceAll("5326", Integer.toString(id))
            .replaceAll("2j8UKw1bRO283PMwDugNNg", localNode)
            .replaceAll("2023-07-31T15:46:32\\.328Z", DateFieldMapper.DEFAULT_DATE_TIME_FORMATTER.formatMillis(taskInfo.startTime()))
            .replaceAll("1690818392328", Long.toString(taskInfo.startTime()))
            .replaceAll("41.7ms", TimeValue.timeValueNanos(taskInfo.runningTimeNanos()).toString())
            .replaceAll("41770830", Long.toString(taskInfo.runningTimeNanos()))
            .trim();
        assertThat(json, equalTo(expected));
    }

    private static void assertParserErrorMessage(String json, String message) {
        Exception e = expectThrows(IllegalArgumentException.class, () -> parseEsqlQueryRequest(json));
        assertThat(e.getMessage(), containsString(message));
    }

    private static EsqlQueryRequest parseEsqlQueryRequest(String json) throws IOException {
        SearchModule searchModule = new SearchModule(Settings.EMPTY, Collections.emptyList());
        XContentParserConfiguration config = XContentParserConfiguration.EMPTY.withRegistry(
            new NamedXContentRegistry(searchModule.getNamedXContents())
        );
        try (XContentParser parser = XContentType.JSON.xContent().createParser(config, json)) {
            return EsqlQueryRequest.fromXContent(parser);
        }
    }

    private static QueryBuilder randomQueryBuilder() {
        return randomFrom(
            new TermQueryBuilder(randomAlphaOfLength(5), randomAlphaOfLengthBetween(1, 10)),
            new RangeQueryBuilder(randomAlphaOfLength(5)).gt(randomIntBetween(0, 1000))
        );
    }
}
