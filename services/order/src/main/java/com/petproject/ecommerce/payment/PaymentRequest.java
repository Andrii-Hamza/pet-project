package com.petproject.ecommerce.payment;

import com.petproject.ecommerce.customer.CustomerResponse;
import com.petproject.ecommerce.order.PaymentMethod;

import java.math.BigDecimal;

public record PaymentRequest(
        BigDecimal amount,
        PaymentMethod paymentMethod,
        Integer orderId,
        String orderReference,
        CustomerResponse customer
) {
}
