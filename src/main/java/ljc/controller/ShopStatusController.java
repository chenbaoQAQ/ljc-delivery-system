package ljc.controller;

import ljc.entity.DeliveryTemplate;
import ljc.service.ShopDeliveryService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.util.*;

@RestController
@RequestMapping("/api/status")
@CrossOrigin
public class ShopStatusController {
    @Autowired private ShopDeliveryService service;

    @GetMapping("/templates")
    public List<DeliveryTemplate> getT(@RequestParam String ym) { return service.getTemplatesByMonth(ym); }

    @PostMapping("/template")
    public String saveT(@RequestBody DeliveryTemplate t) {
        service.saveTemplate(t); // 修复爆红点
        return "SUCCESS";
    }

    @DeleteMapping("/template/{id}")
    public String deleteT(@PathVariable Integer id) {
        service.deleteTemplate(id);
        return "SUCCESS";
    }

    @GetMapping("/auto-report")
    public Map<String, Object> report(@RequestParam String ym, @RequestParam(defaultValue="1") int current) {
        return service.getAutoAuditReport(ym, current, 10);
    }

    @GetMapping("/exception-matrix")
    public Map<String, Object> exMatrix(@RequestParam String ym) {
        return service.getGlobalExceptionMatrix(ym);
    }

    @PostMapping("/upload-csv")
    public Map<String, Object> upload(@RequestParam MultipartFile file, @RequestParam String ym) {
        try {
            service.importCsv(file.getInputStream(), ym);
            return Map.of("status", "SUCCESS");
        } catch (Exception e) {
            return Map.of("status", "ERROR", "message", e.getMessage());
        }
    }
    @GetMapping("/export-qualified")
    public void exportQualified(@RequestParam String ym, javax.servlet.http.HttpServletResponse response) throws Exception {
        String csvData = service.exportQualifiedShopsCsv(ym);

        response.setContentType("text/csv;charset=UTF-8");
        response.setHeader("Content-Disposition", "attachment; filename=qualified_shops_" + ym + ".csv");

        // 写入 UTF-8 BOM 防止 Excel 打开乱码
        response.getOutputStream().write(new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF});
        response.getOutputStream().write(csvData.getBytes("UTF-8"));
    }
}