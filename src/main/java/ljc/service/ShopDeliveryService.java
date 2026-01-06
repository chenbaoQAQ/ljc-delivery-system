package ljc.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
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

@Service
public class ShopDeliveryService extends ServiceImpl<DeliveryDetailMapper, DeliveryDetail> {

    @Autowired
    private DeliveryTemplateMapper templateMapper;

    public IPage<DeliveryDetail> getDetailPage(String yearMonth, int current, int size) {
        return this.page(new Page<>(current, size), new LambdaQueryWrapper<DeliveryDetail>()
                .likeRight(DeliveryDetail::getDate, yearMonth)
                .orderByAsc(DeliveryDetail::getDate));
    }

    public List<DeliveryTemplate> getTemplatesByMonth(String ym) {
        return templateMapper.selectList(new LambdaQueryWrapper<DeliveryTemplate>().eq(DeliveryTemplate::getYearMonth, ym));
    }

    public void saveTemplate(DeliveryTemplate t) {
        if (t.getId() == null) templateMapper.insert(t);
        else templateMapper.updateById(t);
    }

    public void deleteTemplate(Integer id) {
        templateMapper.deleteById(id);
    }

    public Map<String, Object> getAutoAuditReport(String yearMonth, int current, int size) {
        List<DeliveryTemplate> templates = getTemplatesByMonth(yearMonth);
        List<Map<String, Object>> allDetails = baseMapper.selectMonthlyDeliveryStatus(yearMonth);

        Map<String, Map<Integer, Integer>> shopData = new HashMap<>();
        for (Map<String, Object> m : allDetails) {
            String sid = m.get("shopId").toString();
            int day = LocalDate.parse(m.get("date").toString()).getDayOfMonth();
            shopData.computeIfAbsent(sid, k -> new HashMap<>()).put(day, ((Number) m.get("totalQty")).intValue());
        }

        Map<String, String> bestTemplateMap = new HashMap<>();
        Map<String, Set<Integer>> diffDaysMap = new HashMap<>();
        List<String> sortedShops = new ArrayList<>(shopData.keySet());
        Collections.sort(sortedShops);

        for (String sid : sortedShops) {
            Map<Integer, Integer> dailyActual = shopData.get(sid);
            String bestTName = "无匹配底板";
            int minDiffCount = Integer.MAX_VALUE;
            Set<Integer> bestDiffDays = new HashSet<>();

            for (DeliveryTemplate t : templates) {
                String[] cfg = t.getConfig().split(",");
                int currentDiffCount = 0;
                Set<Integer> currentDiffDays = new HashSet<>();

                // 遍历全月天数进行比对
                for (int i = 0; i < cfg.length; i++) {
                    int day = i + 1;
                    int should = Integer.parseInt(cfg[i].trim());
                    int actual = dailyActual.getOrDefault(day, 0) > 0 ? 1 : 0;

                    // 需求核心：只要不相等，就是审计差异 (包括该送没送，和不该送却送了)
                    if (should != actual) {
                        currentDiffCount++;
                        currentDiffDays.add(day);
                    }
                }

                if (currentDiffCount < minDiffCount) {
                    minDiffCount = currentDiffCount;
                    bestTName = t.getTemplateName();
                    bestDiffDays = currentDiffDays;
                }
            }
            bestTemplateMap.put(sid, bestTName);
            diffDaysMap.put(sid, bestDiffDays);
        }

        int start = (current - 1) * size;
        int end = Math.min(start + size, sortedShops.size());
        List<String> pagedShops = (start < sortedShops.size()) ? sortedShops.subList(start, end) : new ArrayList<>();

        List<Map<String, Object>> records = new ArrayList<>();
        int daysInMonth = LocalDate.parse(yearMonth + "-01").lengthOfMonth();
        for (int i = 1; i <= daysInMonth; i++) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("Date", String.format("%02d", i));
            for (String sid : pagedShops) {
                // 如果当天属于审计差异天
                if (diffDaysMap.get(sid).contains(i)) {
                    int val = shopData.get(sid).getOrDefault(i, 0);
                    // 即使送货量为0，只要违反底板，就显示"0"
                    row.put(sid, String.valueOf(val));
                } else {
                    row.put(sid, "-"); // 完全符合底板，显示横杠
                }
            }
            records.add(row);
        }

        Map<String, Object> res = new HashMap<>();
        res.put("records", records);
        res.put("shopList", pagedShops);
        res.put("bestTemplates", bestTemplateMap);
        res.put("total", sortedShops.size());
        res.put("pages", (int) Math.ceil((double) sortedShops.size() / size));
        res.put("current", current);
        return res;
    }

    @Transactional(rollbackFor = Exception.class)
    public void importCsv(InputStream is, String yearMonth) throws Exception {
        this.remove(new LambdaQueryWrapper<DeliveryDetail>().likeRight(DeliveryDetail::getDate, yearMonth));
        try (BufferedReader br = new BufferedReader(new InputStreamReader(is, "UTF-8"))) {
            br.readLine();
            List<DeliveryDetail> batch = new ArrayList<>();
            String line;
            while ((line = br.readLine()) != null) {
                String[] cols = line.split(",");
                if (cols.length < 4) continue;
                DeliveryDetail d = new DeliveryDetail();
                d.setShopId(cols[0].trim());
                d.setSkuId(cols[1].trim());
                d.setQty(Integer.parseInt(cols[2].trim()));
                d.setDate(LocalDate.parse(cols[3].trim()));
                batch.add(d);
                if (batch.size() >= 1000) { this.saveBatch(batch); batch.clear(); }
            }
            if (!batch.isEmpty()) this.saveBatch(batch);
        }
    }
}