package com.yanbin.stock.stocktaskservice.utils;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.emotibot.gemini.geminiutils.pojo.response.ArrayResponse;
import com.emotibot.gemini.geminiutils.utils.HttpHelper;
import com.emotibot.gemini.geminiutils.utils.JsonUtils;
import com.emotibot.gemini.geminiutils.utils.MinioHelper;
import com.emotibot.gemini.geminiutils.utils.RedisHelper;
import com.yanbin.stock.stocktaskservice.dao.data.StockDao;
import com.yanbin.stock.stocktaskservice.dao.data.StockToIndustryDao;
import com.yanbin.stock.stocktaskservice.dao.data.StockTsDao;
import com.yanbin.stock.stocktaskservice.entity.data.StockToIndustryEntity;
import com.yanbin.stock.stocktaskservice.entity.data.StockTsEntity;
import com.yanbin.stock.stocktaskutils.constants.StockTaskConstants;
import com.yanbin.stock.stocktaskutils.pojo.data.Industry;
import com.yanbin.stock.stocktaskutils.pojo.data.Stock;
import com.yanbin.stock.stocktaskutils.pojo.data.StockTs;
import com.yanbin.stock.stocktaskutils.pojo.request.WeiCaiTokenRequest;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.IOUtils;
import org.joda.time.DateTime;
import org.joda.time.format.DateTimeFormat;
import org.joda.time.format.DateTimeFormatter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * @author yanbinwang@emotibot.com
 * @date 2020/9/23 上午8:34
 *
 * 获取股票数据工具类，包括某只股票的实时数据，历史分时数据等
 *
 * 有些是可以通过spider来调用，spider提供代理和cookie等能力
 */
@Slf4j
@Component
public class StockDataHelper {

    // 问财
    private static final String WEN_CAI_URL = "http://www.iwencai.com/unifiedwap/unified-wap/v2/result/get-robot-data?source=Ths_iwencai_Xuangu&version=2.0";
    private static final String WEN_CAI_ADD_INFO = "{\"urp\":{\"scene\":1,\"company\":1,\"business\":8},\"contentType\":\"json\"}";
    private static final String SPIDER_INDUSTRY_TOP_URL = "/gemini/spider/data/industry/top";
    private static final String WEN_CAI_SUGGEST_URL = "http://www.iwencai.com/unifiedwap/suggest/V1/index/query-hint-list";

    // 问财股票信息KEY
    private static final String WEN_CAI_STOCK_CODE_KEY = "code";
    private static final String WEN_CAI_STOCK_NAME_KEY = "股票简称";
    // 及时信息
    private static final String WEN_CAI_STOCK_CURRENT_PRICE_KEY = "最新价";
    private static final String WEN_CAI_STOCK_CURRENT_GROUP_RATE_KEY = "最新涨跌幅";

    private static final DateTimeFormatter STOCK_TIME_FORMATTER = DateTimeFormat.forPattern("HH:mm:ss");

    private static final DateTimeFormatter STOCK_TS_TIME_FORMATTER = DateTimeFormat.forPattern("HHmm");

    @Value("${spider.url}")
    String spiderUrl;

    @Autowired
    HttpHelper httpHelper;

    @Autowired
    StockTsDao stockTsDao;

    @Autowired
    StockDao stockDao;

    @Autowired
    StockToIndustryDao stockToIndustryDao;

    @Autowired
    MinioHelper minioHelper;

    @Autowired
    RedisHelper redisHelper;

    /**
     * 目前问财接口只需要返回code就可以了
     *
     * @param query
     * @return
     */
    public List<Stock> fetchStockByWenCai(String query) {
        String token = getWenCaiToken();
        if (StringUtils.isEmpty(token)) {
            log.error("wen cai token is empty");
            return null;
        }

        Map<String, Object> formMap = new HashMap<>();
        formMap.put("question", query);
        formMap.put("page", 1);
        formMap.put("perpage", 20);

        Map<String, String> headerMap = new HashMap<>();
        headerMap.put("user-agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/54.0.2840.99 Safari/537.36");
        headerMap.put("hexin-v", token);

        JSONObject jsonObject = httpHelper.postForm(WEN_CAI_URL, headerMap, null, formMap, JSONObject.class);
        Integer code = jsonObject.getInteger("status_code");
        if (code != 0) {
            log.error("wen cai");
            return null;
        }
        try {
            List<Stock> stocks = new ArrayList<>();
            JSONArray dataObj = jsonObject.getJSONObject("data").getJSONArray("answer").getJSONObject(0)
                    .getJSONArray("txt").getJSONObject(0).getJSONObject("content").getJSONArray("components")
                    .getJSONObject(0).getJSONObject("data").getJSONArray("datas");
            IntStream.range(0, dataObj.size()).forEach(t ->
                    stocks.add(Stock.builder().code(dataObj.getJSONObject(t).getString(WEN_CAI_STOCK_CODE_KEY))
                            .name(dataObj.getJSONObject(t).getString(WEN_CAI_STOCK_NAME_KEY))
                            .price(dataObj.getJSONObject(t).getDouble(WEN_CAI_STOCK_CURRENT_PRICE_KEY))
                            .growthRate(dataObj.getJSONObject(t).getDouble(WEN_CAI_STOCK_CURRENT_GROUP_RATE_KEY)).build()));
            return stocks;
        } catch (Exception e) {
            e.printStackTrace();
            log.error("wen cai data");
            return null;
        }
    }

    public List<Stock> fetchStockByWenCai(List<String> queries) {
        List<Stock> chosenStock = new ArrayList<>();
        for (String query : queries) {
            List<Stock> stocks = fetchStockByWenCai(query);
            // 一旦没有选出的股票
            if (CollectionUtils.isEmpty(stocks)) {
                return null;
            } else {
                if (CollectionUtils.isEmpty(chosenStock)) {
                    chosenStock.addAll(stocks);
                } else {
                    // 需要对于chosenStock和stocks取交集，遍历chosenStock，如果没有
                    Map<String, Stock> codeToStockMap = chosenStock.stream().collect(Collectors.toMap(Stock::getName,
                            Function.identity()));
                    chosenStock = stocks.stream().filter(t -> codeToStockMap.containsKey(t.getCode()))
                            .collect(Collectors.toList());
                }
            }
            if (CollectionUtils.isEmpty(chosenStock)) {
                return null;
            }
        }
        return chosenStock;
    }

    /**
     * 1. 先获取minio的文件路径，时间是11:00
     * 2. 再获取json内容，按照时间获取结果
     *
     * @param code
     * @param dateTime
     * @return
     */
    public Stock fetchStockByTime(String code, DateTime dateTime) {
        // 日期搜索条件
        DateTime date = new DateTime(dateTime);
        date = date.withHourOfDay(0).withMinuteOfHour(0).withSecondOfMinute(0).withMillisOfSecond(0);
        StockTsEntity stockTsEntity = stockTsDao.findOneByCodeAndTime(code, new Timestamp(date.getMillis()));
        if (stockTsEntity == null) {
            return null;
        }
        InputStream inputStream = minioHelper.download(StockTaskConstants.SPIDER_BUCKET, stockTsEntity.getData());
        String stockTsStr;
        try {
            stockTsStr = IOUtils.toString(inputStream, String.valueOf(StandardCharsets.UTF_8));
        } catch (IOException e) {
            e.printStackTrace();
            return null;
        }
        StockTs stockTs = JsonUtils.getObjectFromStr(stockTsStr, StockTs.class);
        StockTs.StockTsElement stockTsElement =
                stockTs.getElements().stream().filter(t -> t.getTime().equals(dateTime.toString(STOCK_TS_TIME_FORMATTER)))
                        .findFirst().orElse(null);
        if (stockTsElement == null) {
            return null;
        }
        return Stock.builder().code(code).price(stockTsElement.getPrice()).build();
    }

    // 调用爬虫服务，获取结果
    public List<Industry> fetchTopIndustry(Integer size) {
        Map<String, Object> paramMap = new HashMap<>();
        paramMap.put("size", size);
        ArrayResponse response = httpHelper.get(spiderUrl + SPIDER_INDUSTRY_TOP_URL, null, paramMap, ArrayResponse.class);
        if (response == null || !response.success()) {
            return null;
        }
        return httpHelper.buildResponseDataList(response, Industry.class);
    }

    public Map<String, List<String>> getStockToIndustryMap() {
        List<StockToIndustryEntity> stockToIndustryEntities = stockToIndustryDao.findAll();
        if (CollectionUtils.isEmpty(stockToIndustryEntities)) {
            return null;
        }
        return stockToIndustryEntities.stream().collect(Collectors.groupingBy(StockToIndustryEntity::getStockCode,
                Collectors.mapping(StockToIndustryEntity::getIndustryName, Collectors.toList())));
    }

    public List<String> wenCaiSuggest(String query) {

        Map<String, Object> paramMap = new HashMap<>();
        paramMap.put("dataType", "lenovo");
        paramMap.put("num", 5);
        paramMap.put("q", query);
        paramMap.put("queryType", "info");

        Map<String, String> headerMap = new HashMap<>();
        headerMap.put("user-agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/54.0.2840.99 Safari/537.36");

        JSONObject jsonObject = httpHelper.get(WEN_CAI_SUGGEST_URL, headerMap, paramMap, JSONObject.class);
        Boolean ret = jsonObject.getBoolean("success");
        if (!ret) {
            log.error("wen cai suggest failed");
            return null;
        }
        try {
            List<String> result = new ArrayList<>();
            JSONArray dataObj = jsonObject.getJSONObject("data").getJSONArray("docs");
            IntStream.range(0, dataObj.size()).forEach(t -> result.add(dataObj.getString(t)));
            return result;
        } catch (Exception e) {
            e.printStackTrace();
            log.error("wen cai suggest data invalid");
            return null;
        }
    }

    public void addWenCaiToken(WeiCaiTokenRequest weiCaiTokenRequest) {
        if (StringUtils.isEmpty(weiCaiTokenRequest.getToken())) {
            return;
        }
        redisHelper.set(StockTaskConstants.REDIS_WENCAI_TOKEN_KEY, weiCaiTokenRequest.getToken());
    }

    public String getWenCaiToken() {
        return (String) redisHelper.get(StockTaskConstants.REDIS_WENCAI_TOKEN_KEY);
    }

    public void deleteWenCaiToken() {
        redisHelper.remove(StockTaskConstants.REDIS_WENCAI_TOKEN_KEY);
    }
}
