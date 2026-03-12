package com.petproject.ecommerce.kafka;

import com.petproject.ecommerce.customer.CustomerResponse;
import com.petproject.ecommerce.order.PaymentMethod;
import com.petproject.ecommerce.product.PurchaseResponse;

import java.math.BigDecimal;
import java.util.List;

public record OrderConfirmation(
        String orderReference,
        BigDecimal totalAmount,
        PaymentMethod paymentMethod,
        CustomerResponse customer,
        List<PurchaseResponse> products

) {
}
