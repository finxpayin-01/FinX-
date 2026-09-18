package com.finx.payment.dto.onramp;
import java.util.List;
public class PaymentMethodsResponse {
    private List<String> methods;
    public List<String> getMethods() { return methods; }
    public void setMethods(List<String> methods) { this.methods = methods; }
}
