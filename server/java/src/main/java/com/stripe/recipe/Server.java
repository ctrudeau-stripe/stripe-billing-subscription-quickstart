package com.stripe.recipe;

import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

import static spark.Spark.get;
import static spark.Spark.post;
import static spark.Spark.staticFiles;
import static spark.Spark.port;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.annotations.SerializedName;

import com.stripe.Stripe;
import com.stripe.model.Customer;
import com.stripe.model.Event;
import com.stripe.model.Subscription;
import com.stripe.exception.*;
import com.stripe.net.Webhook;

public class Server {
    private static Gson gson = new Gson();

    static class CreatePaymentBody {
        @SerializedName("payment_method")
        String paymentMethod;
        @SerializedName("email")
        String email;

        public String getPaymentMethod() {
            return paymentMethod;
        }

        public String getEmail() {
            return email;
        }
    }

    public static void main(String[] args) {
        port(4242);
        Stripe.apiKey = System.getenv("STRIPE_SECRET_KEY");

        staticFiles.externalLocation(
                Paths.get(Paths.get("").toAbsolutePath().getParent().getParent().toString() + "/client")
                        .toAbsolutePath().toString());

        get("/public-key", (request, response) -> {
            response.type("application/json");
            JsonObject publicKey = new JsonObject();
            publicKey.addProperty("publicKey", System.getenv("STRIPE_PUBLIC_KEY"));
            return publicKey.toString();
        });

        post("/create-customer", (request, response) -> {
            response.type("application/json");

            CreatePaymentBody postBody = gson.fromJson(request.body(), CreatePaymentBody.class);
            // This creates a new Customer and attaches the PaymentMethod in one API call.
            Map<String, Object> customerParams = new HashMap<String, Object>();
            customerParams.put("payment_method", postBody.getPaymentMethod());
            customerParams.put("email", postBody.getEmail());
            Map<String, String> invoiceSettings = new HashMap<String, String>();
            invoiceSettings.put("default_payment_method", postBody.getPaymentMethod());
            customerParams.put("invoice_settings", invoiceSettings);

            Customer customer = Customer.create(customerParams);

            // Subscribe customer to a plan
            Map<String, Object> item = new HashMap<>();
            item.put("plan", "plan_FSDjyHWis0QVwl");
            Map<String, Object> items = new HashMap<>();
            items.put("0", item);

            Map<String, Object> expand = new HashMap<>();
            expand.put("0", "latest_invoice.payment_intent");
            Map<String, Object> params = new HashMap<>();
            params.put("customer", customer.getId());
            params.put("items", items);
            params.put("expand", expand);
            Subscription subscription = Subscription.create(params);

            return subscription.toJson();
        });

        post("/webhook", (request, response) -> {
            System.out.println("Webhook");
            String payload = request.body();
            String sigHeader = request.headers("Stripe-Signature");
            String endpointSecret = System.getenv("STRIPE_WEBHOOK_SECRET");

            Event event = null;

            try {
                event = Webhook.constructEvent(payload, sigHeader, endpointSecret);
            } catch (SignatureVerificationException e) {
                // Invalid signature
                response.status(400);
                return "";
            }

            switch (event.getType()) {
            case "payment_intent.succeeded":
                System.out.println("Received event");
                break;
            default:
                // Unexpected event type
                response.status(400);
                return "";
            }

            response.status(200);
            return "";
        });
    }
}