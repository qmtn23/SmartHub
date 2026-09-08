package com.hmdp.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.dto.ConsultationContextDTO;
import com.hmdp.entity.Shop;
import com.hmdp.entity.Voucher;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.mapper.VoucherMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ProductConsultationServiceTest {
    private final ShopMapper shops=mock(ShopMapper.class);
    private final VoucherMapper vouchers=mock(VoucherMapper.class);
    private final ProductConsultationService service=new ProductConsultationService(shops,vouchers,mock(JdbcTemplate.class),new ObjectMapper());
    @Test void serverDerivesShopAndCategoryFromVoucher(){
        when(vouchers.selectById(7L)).thenReturn(new Voucher().setId(7L).setShopId(1L).setTitle("双人券").setPayValue(9900L));
        when(shops.selectById(1L)).thenReturn(new Shop().setId(1L).setTypeId(2L).setName("店铺"));
        ConsultationContextDTO ids=new ConsultationContextDTO();ids.setVoucherId(7L);
        Map<String,Object> value=service.resolve(ids);
        assertEquals(1L,value.get("shopId"));assertEquals(2L,value.get("categoryId"));assertEquals(9900L,value.get("payValue"));
    }
    @Test void forgedShopOrCategoryIsRejected(){
        when(vouchers.selectById(7L)).thenReturn(new Voucher().setId(7L).setShopId(1L));
        ConsultationContextDTO ids=new ConsultationContextDTO();ids.setVoucherId(7L);ids.setShopId(2L);
        assertThrows(ResponseStatusException.class,()->service.resolve(ids));
        verifyNoInteractions(shops);
    }
    @Test void explicitEmptyObjectClearsOldProduct(){assertTrue(service.resolve(new ConsultationContextDTO()).isEmpty());}
    @Test void categoryWithoutShopIsNotGlobalMerchantPermission(){
        ConsultationContextDTO ids=new ConsultationContextDTO();ids.setCategoryId(2L);
        assertEquals(Map.of("categoryId",2L),service.resolve(ids));verifyNoInteractions(shops,vouchers);
    }
    @Test void unknownIdentityOrPriceFieldsAreRejected(){
        assertThrows(Exception.class,()->new ObjectMapper().readValue("{\"voucherId\":7,\"userId\":3}",ConsultationContextDTO.class));
    }
}
