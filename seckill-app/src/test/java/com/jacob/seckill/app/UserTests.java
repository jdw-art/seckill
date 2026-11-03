package com.jacob.seckill.app;
import com.jacob.seckill.common.domain.dataobject.UserDO;
import com.jacob.seckill.common.domain.mapper.UserDOMapper;import jakarta.annotation.Resource;import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDateTime;


@SpringBootTest
class UserTests {

    @Resource
    private UserDOMapper userDOMapper;


    /**
     * 添加一条用户记录
     */
    @Test
    void testInsertUser() {
        userDOMapper.insert(UserDO.builder()
                        .nickname("jacob")
                        .password("123456")
                        .mobile("18019988888")
                        .status(1)
                        .createTime(LocalDateTime.now())
                        .updateTime(LocalDateTime.now())
                        .build());
    }

}
