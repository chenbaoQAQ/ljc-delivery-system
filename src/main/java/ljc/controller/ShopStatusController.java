package ljc.controller;

import ljc.entity.DeliveryTemplate;
import ljc.service.ShopDeliveryService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import javax.servlet.http.HttpServletResponse;
import java.util.*;

@RestController
@RequestMapping("/api/status")
@CrossOrigin
public class ShopStatusController {

    @Autowired private ShopDeliveryService service;

    @GetMapping("/templates") public List<DeliveryTemplate> getT(@RequestParam String ym) { return service.getTemplatesByMonth(ym); }

    @PostMapping("/template") public String saveT(@RequestBody DeliveryTemplate t) { service.saveTemplate(t); return "SUCCESS"; }

    @DeleteMapping("/template/{id}") public String deleteT(@PathVariable Integer id) { service.deleteTemplate(id); return "SUCCESS"; }

    @GetMapping("/auto-report") public Map<String, Object> report(@RequestParam String ym, @RequestParam(defaultValue="1") int current) {
        return service.getAutoAuditReport(ym, current, 10);
    }

    @GetMapping("/exception-matrix") public Map<String, Object> exMatrix(@RequestParam String ym) {
        return service.getGlobalExceptionMatrix(ym);
    }

    @GetMapping("/export-qualified-list")
    public void exportQualifiedList(@RequestParam String ym, HttpServletResponse response) throws Exception {
        String csv = service.exportQualifiedShopsCsv(ym);
        setupCsvResponse(response, "qualified_list_" + ym + ".csv", csv);
    }

    @GetMapping("/export-exception-matrix")
    public void exportExceptionMatrix(@RequestParam String ym, HttpServletResponse response) throws Exception {
        String csv = service.exportExceptionMatrixCsv(ym);
        setupCsvResponse(response, "exception_status_matrix_" + ym + ".csv", csv);
    }

    @GetMapping("/export-exception-qty")
    public void exportExceptionQty(@RequestParam String ym, HttpServletResponse response) throws Exception {
        String csv = service.exportExceptionQtyMatrixCsv(ym);
        setupCsvResponse(response, "exception_qty_matrix_inverted_" + ym + ".csv", csv);
    }

    @PostMapping("/upload-csv") public Map<String, Object> upload(@RequestParam MultipartFile file, @RequestParam String ym) {
        try { service.importCsv(file.getInputStream(), ym); return Map.of("status", "SUCCESS"); }
        catch (Exception e) { return Map.of("status", "ERROR", "message", e.getMessage()); }
    }

    private void setupCsvResponse(HttpServletResponse response, String fileName, String content) throws Exception {
        response.setContentType("text/csv;charset=UTF-8");
        response.setHeader("Content-Disposition", "attachment; filename=" + fileName);
        response.getOutputStream().write(new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF}); // 写入BOM头
        response.getOutputStream().write(content.getBytes("UTF-8"));
        response.getOutputStream().flush();
    }
}