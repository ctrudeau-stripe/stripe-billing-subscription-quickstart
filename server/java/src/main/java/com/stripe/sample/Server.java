package com.stripe.sample;

import java.nio.file.Paths;

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
import com.stripe.param.CustomerCreateParams;
import com.stripe.param.SubscriptionCreateParams;
import com.stripe.param.CustomerCreateParams.InvoiceSettings;
import com.stripe.param.SubscriptionCreateParams.Item;

import io.github.cdimascio.dotenv.Dotenv;

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

    static class CreateSubscriptionBody {
        @SerializedName("subscriptionId")
        String subscriptionId;

        public String getSubscriptionId() {
            return subscriptionId;
        }
    }

    public static void main(String[] args) {
        port(4242);
        String ENV_PATH = "../../";
        Dotenv dotenv = Dotenv.configure().directory(ENV_PATH).load();

        Stripe.apiKey = dotenv.get("STRIPE_SECRET_KEY");

        staticFiles.externalLocation(
                Paths.get(Paths.get("").toAbsolutePath().toString(), dotenv.get("STATIC_DIR")).normalize().toString());

        get("/public-key", (request, response) -> {
            response.type("application/json");
            JsonObject publicKey = new JsonObject();
            publicKey.addProperty("publicKey", dotenv.get("STRIPE_PUBLIC_KEY"));
            return publicKey.toString();
        });

        post("/create-customer", (request, response) -> {
            response.type("application/json");

            CreatePaymentBody postBody = gson.fromJson(request.body(), CreatePaymentBody.class);

            InvoiceSettings settings = new CustomerCreateParams.InvoiceSettings.Builder()
                    .setDefaultPaymentMethod(postBody.getPaymentMethod()).build();

            // This creates a new Customer and attaches the PaymentMethod in one API call.
            CustomerCreateParams.Builder customerBuilder = new CustomerCreateParams.Builder();
            customerBuilder.setEmail(postBody.getEmail()).setPaymentMethod(postBody.getPaymentMethod())
                    .setInvoiceSettings(settings);

            Customer customer = Customer.create(customerBuilder.build());

            SubscriptionCreateParams createParams = new SubscriptionCreateParams.Builder().setCustomer(customer.getId())
                    .addItem(new Item.Builder().setPlan("plan_FSDjyHWis0QVwl").build())
                    .addExpand("latest_invoice.payment_intent").build();

            Subscription subscription = Subscription.create(createParams);

            return subscription.toJson();
        });

        post("/subscription", (request, response) -> {
            response.type("application/json");

            CreateSubscriptionBody postBody = gson.fromJson(request.body(), CreateSubscriptionBody.class);
            return Subscription.retrieve(postBody.getSubscriptionId()).toJson();
        });

        post("/webhook", (request, response) -> {
            String payload = request.body();
            String sigHeader = request.headers("Stripe-Signature");
            String endpointSecret = dotenv.get("STRIPE_WEBHOOK_SECRET");

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