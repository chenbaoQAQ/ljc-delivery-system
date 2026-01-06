package ljc.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import ljc.entity.DeliveryDetail;
import ljc.service.ShopDeliveryService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.util.*;

@RestController
@RequestMapping("/api/status")
@CrossOrigin
public class ShopStatusController {

    @Autowired
    private ShopDeliveryService shopDeliveryService;

    // 1. 获取门店状态表 (原数量报表更名并修改逻辑)
    @GetMapping("/status-report")
    public Map<String, Object> getStatusReport(@RequestParam String yearMonth, @RequestParam(defaultValue="1") int current) {
        return shopDeliveryService.getShopStatusReport(yearMonth, current, 50);
    }

    // 2. 原始明细表
    @GetMapping("/details")
    public IPage<DeliveryDetail> getDetails(@RequestParam String yearMonth, @RequestParam(defaultValue="1") int current) {
        return shopDeliveryService.getDetailPage(yearMonth, current, 50);
    }

    @PostMapping("/upload-csv")
    public String uploadCsv(@RequestParam("file") MultipartFile file, @RequestParam("yearMonth") String yearMonth) {
        try {
            // 将前端传来的月份带入 service
            shopDeliveryService.importCsv(file.getInputStream(), yearMonth);
            return "SUCCESS";
        } catch (Exception e) {
            return "ERROR: " + e.getMessage();
        }
    }
    @GetMapping("/exception-report")
    public Map<String, Object> getExceptionReport(@RequestParam String yearMonth, @RequestParam(defaultValue="1") int current) {
        // 每页显示 15 个异常门店列
        return shopDeliveryService.getExceptionReport(yearMonth, current, 15);
    }
}