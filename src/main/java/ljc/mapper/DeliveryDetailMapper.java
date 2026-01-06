package ljc.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import ljc.entity.DeliveryDetail;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import java.util.List;
import java.util.Map;

@Mapper
public interface DeliveryDetailMapper extends BaseMapper<DeliveryDetail> {
    // 聚合查询：返回某月内 门店+日期 的有货状态
    @Select("SELECT shop_id, date, SUM(qty) as totalQty " +
            "FROM delivery_detail " +
            "WHERE DATE_FORMAT(date, '%Y-%m') = #{yearMonth} " +
            "GROUP BY shop_id, date")
    List<Map<String, Object>> selectMonthlyDeliveryStatus(String yearMonth);
}