package io.cattle.platform.object.util;

import jakarta.annotation.PostConstruct;

import org.apache.commons.beanutils2.BeanUtilsBean;
import org.apache.commons.beanutils2.ConvertUtilsBean;
import org.apache.commons.beanutils2.converters.IntegerConverter;
import org.apache.commons.beanutils2.converters.LongConverter;

public class CommonsConverterStartup {

    @PostConstruct
    public void init() {
        ConvertUtilsBean service = BeanUtilsBean.getInstance().getConvertUtils();
        service.register(new LongConverter(null), Long.class);
        service.register(new IntegerConverter(null), Integer.class);
    }

}
