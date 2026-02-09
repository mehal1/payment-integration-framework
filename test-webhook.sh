#!/bin/bash

# Webhook Testing Script
# This script will test webhooks step by step

echo "=== Webhook Testing Script ==="
echo ""

# Step 1: Get webhook URL from user
echo "Step 1: Get your webhook.site URL"
echo "1. Go to https://webhook.site"
echo "2. Copy your unique URL (looks like: https://webhook.site/abc123-def456-...)"
echo ""
read -p "Paste your webhook.site URL here: " WEBHOOK_URL

if [[ ! $WEBHOOK_URL =~ ^https://webhook\.site/[a-zA-Z0-9-]+$ ]]; then
    echo "❌ ERROR: Invalid webhook.site URL format!"
    echo "   Expected format: https://webhook.site/YOUR-UNIQUE-ID"
    exit 1
fi

echo "✅ Webhook URL looks valid: $WEBHOOK_URL"
echo ""

# Step 2: Set entity ID
read -p "Enter entity ID (or press Enter for 'test-merchant-123'): " ENTITY_ID
ENTITY_ID=${ENTITY_ID:-test-merchant-123}
echo "Using entity ID: $ENTITY_ID"
echo ""

# Step 3: Register webhook
echo "Step 2: Registering webhook..."
REGISTER_RESPONSE=$(curl -s -X POST http://localhost:8080/api/v1/risk/webhooks \
  -H "Content-Type: application/json" \
  -d "{\"entityId\": \"$ENTITY_ID\", \"webhookUrl\": \"$WEBHOOK_URL\"}")

echo "Registration response: $REGISTER_RESPONSE"
echo ""

# Step 4: Verify registration
echo "Step 3: Verifying webhook registration..."
VERIFY_RESPONSE=$(curl -s "http://localhost:8080/api/v1/risk/webhooks?entityId=$ENTITY_ID")
echo "Verification response: $VERIFY_RESPONSE"
echo ""

# Step 5: Trigger alert
echo "Step 4: Triggering demo alert with merchantRef=$ENTITY_ID..."
echo "Waiting 2 seconds for webhook delivery..."
TRIGGER_RESPONSE=$(curl -s -X POST "http://localhost:8080/api/v1/risk/demo/trigger?merchantRef=$ENTITY_ID")
echo "Trigger response: $TRIGGER_RESPONSE"
echo ""

# Step 6: Wait and check
echo "Step 5: Waiting 3 seconds for async webhook delivery..."
sleep 3

echo ""
echo "=== Check webhook.site now! ==="
echo "Go to: $WEBHOOK_URL"
echo "You should see a POST request with RiskAlert JSON"
echo ""
echo "If you don't see it:"
echo "1. Check application logs for webhook errors"
echo "2. Make sure webhooks are enabled: payment.risk.webhook.enabled=true"
echo "3. Verify entityId matches: $ENTITY_ID"
