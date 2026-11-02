# Monthly cost budget for the whole build account. Alerts email var.alert_email
# directly rather than via the alerts SNS topic: that topic is encrypted with the
# AWS-managed alias/aws/sns key, which AWS Budgets cannot publish to.
# With alert_email unset the budget still tracks spend, but nobody is notified.

resource "aws_budgets_budget" "monthly_cost" {
  name         = "mockserver-build-monthly-cost"
  budget_type  = "COST"
  limit_amount = tostring(var.monthly_cost_budget_usd)
  limit_unit   = "USD"
  time_unit    = "MONTHLY"

  dynamic "notification" {
    for_each = var.alert_email != "" ? [
      { threshold = 80, type = "ACTUAL" },
      { threshold = 100, type = "FORECASTED" },
    ] : []
    content {
      comparison_operator        = "GREATER_THAN"
      threshold                  = notification.value.threshold
      threshold_type             = "PERCENTAGE"
      notification_type          = notification.value.type
      subscriber_email_addresses = [var.alert_email]
    }
  }
}
