package ljc.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import ljc.entity.DeliveryTemplate;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface DeliveryTemplateMapper extends BaseMapper<DeliveryTemplate> {
    // 继承后自动拥有增删改查模板的能力
}