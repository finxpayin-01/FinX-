package com.finx.deal.dto.request;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.UUID;

public class CreateDealRequest {

    @NotBlank(message = "Deal title is required")
    @Size(min = 3, max = 255, message = "Deal title must be between 3 and 255 characters")
    private String title;

    private String description;

    private UUID buyerId;

    @NotBlank(message = "Seller ID or UID is required")
    private String sellerId;

    @NotNull(message = "Total amount is required")
    @DecimalMin(value = "0.01", message = "Total amount must be at least 0.01")
    private BigDecimal totalAmount;

    @jakarta.validation.constraints.Pattern(regexp = "^(?i)INR$", message = "Currency must be INR")
    private String currency = "INR";
    
    private String vendorEmail;

    public CreateDealRequest() {
    }

    public CreateDealRequest(String title, String description, UUID buyerId, UUID sellerId, BigDecimal totalAmount, String currency) {
        this.title = title;
        this.description = description;
        this.buyerId = buyerId;
        this.sellerId = sellerId != null ? sellerId.toString() : null;
        this.totalAmount = totalAmount;
        this.currency = currency;
    }

    public CreateDealRequest(String title, String description, UUID buyerId, String sellerId, BigDecimal totalAmount, String currency) {
        this.title = title;
        this.description = description;
        this.buyerId = buyerId;
        this.sellerId = sellerId;
        this.totalAmount = totalAmount;
        this.currency = currency;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public UUID getBuyerId() {
        return buyerId;
    }

    public void setBuyerId(UUID buyerId) {
        this.buyerId = buyerId;
    }

    public String getSellerId() {
        return sellerId;
    }

    public void setSellerId(String sellerId) {
        this.sellerId = sellerId;
    }

    public void setSellerId(UUID sellerId) {
        this.sellerId = sellerId != null ? sellerId.toString() : null;
    }

    public BigDecimal getTotalAmount() {
        return totalAmount;
    }

    public void setTotalAmount(BigDecimal totalAmount) {
        this.totalAmount = totalAmount;
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }

    public String getVendorEmail() {
        return vendorEmail;
    }

    public void setVendorEmail(String vendorEmail) {
        this.vendorEmail = vendorEmail;
    }
}
