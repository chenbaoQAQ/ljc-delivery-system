package ljc.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import ljc.entity.DeliveryDetail;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import java.util.List;
import java.util.Map;

public interface DeliveryDetailMapper extends BaseMapper<DeliveryDetail> {

    /**
     * 高性能局部聚合查询：
     * 只查询当前页涉及的门店在指定月份的配送汇总
     */
    @Select("<script>" +
            "SELECT shop_id as shopId, date, SUM(qty) as totalQty " +
            "FROM delivery_detail " +
            "WHERE date LIKE CONCAT(#{yearMonth}, '%') " +
            "AND shop_id IN " +
            "<foreach item='id' collection='shopIds' open='(' separator=',' close=')'>" +
            "  #{id}" +
            "</foreach> " +
            "GROUP BY shop_id, date" +
            "</script>")
    List<Map<String, Object>> selectMonthlySummaryByShops(
            @Param("yearMonth") String yearMonth,
            @Param("shopIds") List<String> shopIds);

    /**
     * 获取全月所有门店的配送状态（用于异常报表初筛）
     */
    @Select("SELECT shop_id as shopId, date, SUM(qty) as totalQty " +
            "FROM delivery_detail " +
            "WHERE date LIKE CONCAT(#{yearMonth}, '%') " +
            "GROUP BY shop_id, date")
    List<Map<String, Object>> selectMonthlyDeliveryStatus(@Param("yearMonth") String yearMonth);
}