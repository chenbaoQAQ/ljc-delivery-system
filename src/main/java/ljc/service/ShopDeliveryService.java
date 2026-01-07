package ljc.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import ljc.entity.DeliveryDetail;
import ljc.entity.DeliveryTemplate;
import ljc.mapper.DeliveryDetailMapper;
import ljc.mapper.DeliveryTemplateMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class ShopDeliveryService extends ServiceImpl<DeliveryDetailMapper, DeliveryDetail> {

    @Autowired
    private DeliveryTemplateMapper templateMapper;

    /**
     * 原始明细分页查询 (真正的物理分页)
     */
    public IPage<DeliveryDetail> getDetailPage(String yearMonth, int current, int size) {
        return this.page(new Page<>(current, size), new LambdaQueryWrapper<DeliveryDetail>()
                .likeRight(DeliveryDetail::getDate, yearMonth).orderByAsc(DeliveryDetail::getDate));
    }

    /**
     * 自动化审计报表 - 核心重构版
     */
    public Map<String, Object> getAutoAuditReport(String yearMonth, int current, int size) {
        // 1. 获取该月底板模板
        List<DeliveryTemplate> templates = getTemplatesByMonth(yearMonth);

        // 2. 第一步：分页获取去重的门店ID (利用 idx_date_shop 索引)
        Page<Map<String, Object>> idPage = new Page<>(current, size);
        QueryWrapper<DeliveryDetail> idQuery = new QueryWrapper<>();
        idQuery.select("distinct shop_id")
                .likeRight("date", yearMonth)
                .orderByAsc("shop_id");

        IPage<Map<String, Object>> resultPage = baseMapper.selectMapsPage(idPage, idQuery);
        List<String> pagedShopIds = resultPage.getRecords().stream()
                .map(m -> m.get("shop_id").toString())
                .collect(Collectors.toList());

        if (pagedShopIds.isEmpty()) return new HashMap<>();

        // 3. 第二步：局部聚合 (仅针对这几个店，利用 idx_shop_date_qty 索引)
        List<Map<String, Object>> pagedDetails = baseMapper.selectMonthlySummaryByShops(yearMonth, pagedShopIds);

        // 4. 转换数据结构
        Map<String, Map<Integer, Integer>> shopData = new HashMap<>();
        for (Map<String, Object> m : pagedDetails) {
            String sid = m.get("shopId").toString();
            int day = LocalDate.parse(m.get("date").toString()).getDayOfMonth();
            shopData.computeIfAbsent(sid, k -> new HashMap<>()).put(day, ((Number) m.get("totalQty")).intValue());
        }

        // 5. 循环计算审计状态，并收集三类视图所需数据
        List<Map<String, Object>> records = new ArrayList<>();      // 看板大表
        List<Map<String, Object>> auditSummary = new ArrayList<>(); // 全量列表
        List<Map<String, Object>> exceptionList = new ArrayList<>(); // 异常清单
        int daysInMonth = LocalDate.parse(yearMonth + "-01").lengthOfMonth();

        for (String sid : pagedShopIds) {
            Map<Integer, Integer> dailyActual = shopData.getOrDefault(sid, new HashMap<>());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("shopId", sid);

            // 寻找最佳匹配底板
            DeliveryTemplate bestT = findBestTemplate(dailyActual, templates, daysInMonth);
            row.put("bestTemplate", bestT != null ? bestT.getTemplateName() : "无匹配");
            String sign = (bestT != null) ? bestT.getTemplateName().substring(0, 1) : "?";
            String[] bestCfg = (bestT != null) ? bestT.getConfig().split(",") : new String[daysInMonth];

            for (int d = 1; d <= daysInMonth; d++) {
                int actualStatus = dailyActual.getOrDefault(d, 0) > 0 ? 1 : 0;
                int shouldStatus = (bestT != null && d <= bestCfg.length) ? Integer.parseInt(bestCfg[d-1].trim()) : 0;
                boolean isError = (actualStatus != shouldStatus);
                String statusStr = actualStatus + sign;

                // 填充矩阵行
                row.put("day" + d, statusStr);
                row.put("isError" + d, isError);

                // 填充全量列表
                Map<String, Object> sItem = new HashMap<>();
                sItem.put("shopId", sid);
                sItem.put("date", yearMonth + "-" + String.format("%02d", d));
                sItem.put("status", statusStr);
                sItem.put("isError", isError);
                auditSummary.add(sItem);

                // 填充异常清单
                if (isError) {
                    Map<String, Object> eItem = new HashMap<>();
                    eItem.put("shopId", sid);
                    eItem.put("date", yearMonth + "-" + String.format("%02d", d));
                    exceptionList.add(eItem);
                }
            }
            records.add(row);
        }

        // 6. 返回前端所需的所有字段
        Map<String, Object> res = new HashMap<>();
        res.put("records", records);
        res.put("auditSummary", auditSummary);
        res.put("exceptionList", exceptionList);
        res.put("days", daysInMonth);
        res.put("total", resultPage.getTotal());
        res.put("pages", resultPage.getPages());
        res.put("current", current);
        return res;
    }

    private DeliveryTemplate findBestTemplate(Map<Integer, Integer> dailyActual, List<DeliveryTemplate> templates, int days) {
        DeliveryTemplate bestT = null;
        int minDiff = Integer.MAX_VALUE;
        for (DeliveryTemplate t : templates) {
            String[] cfg = t.getConfig().split(",");
            int diff = 0;
            for (int i = 0; i < cfg.length; i++) {
                int should = Integer.parseInt(cfg[i].trim());
                int actual = dailyActual.getOrDefault(i + 1, 0) > 0 ? 1 : 0;
                if (should != actual) diff++;
            }
            if (diff < minDiff) { minDiff = diff; bestT = t; }
        }
        return bestT;
    }

    public List<DeliveryTemplate> getTemplatesByMonth(String ym) {
        return templateMapper.selectList(new LambdaQueryWrapper<DeliveryTemplate>().eq(DeliveryTemplate::getYearMonth, ym));
    }

    public void saveTemplate(DeliveryTemplate t) {
        if (t.getId() == null) templateMapper.insert(t);
        else templateMapper.updateById(t);
    }

    public void deleteTemplate(Integer id) { templateMapper.deleteById(id); }

    @Transactional(rollbackFor = Exception.class)
    public void importCsv(InputStream is, String yearMonth) throws Exception {
        String currentBatchNo = UUID.randomUUID().toString();
        this.remove(new LambdaQueryWrapper<DeliveryDetail>().likeRight(DeliveryDetail::getDate, yearMonth));
        try (BufferedReader br = new BufferedReader(new InputStreamReader(is, "UTF-8"))) {
            br.readLine();
            List<DeliveryDetail> batch = new ArrayList<>();
            String line;
            while ((line = br.readLine()) != null) {
                String[] cols = line.split(",");
                if (cols.length < 4 || !cols[3].trim().startsWith(yearMonth)) continue;
                DeliveryDetail d = new DeliveryDetail();
                d.setShopId(cols[0].trim()); d.setSkuId(cols[1].trim());
                d.setQty(Integer.parseInt(cols[2].trim())); d.setDate(LocalDate.parse(cols[3].trim()));
                d.setBatchNo(currentBatchNo);
                batch.add(d);
                if (batch.size() >= 1000) { this.saveBatch(batch); batch.clear(); }
            }
            if (!batch.isEmpty()) this.saveBatch(batch);
        }
    }
}