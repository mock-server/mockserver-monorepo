locals {
  stacks = {
    default = {
      asg_name             = module.buildkite_stack.auto_scaling_group_name
      lambda_function_name = module.buildkite_stack.scaler_lambda_function_name
      scaler_log_group     = module.buildkite_stack.scaler_log_group
      not_invoked_period   = 300 # scaler runs every minute
    }
    release = {
      asg_name             = module.buildkite_release_stack.auto_scaling_group_name
      lambda_function_name = module.buildkite_release_stack.scaler_lambda_function_name
      scaler_log_group     = module.buildkite_release_stack.scaler_log_group
      not_invoked_period   = 600 # scaler runs every 5 minutes; a 300 s window could miss it
    }
  }
}

resource "aws_sns_topic" "buildkite_alerts" {
  name              = "buildkite-mockserver-alerts"
  display_name      = "Buildkite Agent Infrastructure Alerts"
  kms_master_key_id = "alias/aws/sns"
}

resource "aws_sns_topic_subscription" "buildkite_alerts_email" {
  count     = var.alert_email != "" ? 1 : 0
  topic_arn = aws_sns_topic.buildkite_alerts.arn
  protocol  = "email"
  endpoint  = var.alert_email
}

data "aws_caller_identity" "current" {}

resource "aws_sns_topic_policy" "buildkite_alerts" {
  arn = aws_sns_topic.buildkite_alerts.arn

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid    = "AllowCloudWatchAlarms"
        Effect = "Allow"
        Principal = {
          Service = "cloudwatch.amazonaws.com"
        }
        Action   = "SNS:Publish"
        Resource = aws_sns_topic.buildkite_alerts.arn
        Condition = {
          StringEquals = {
            "aws:SourceAccount" = data.aws_caller_identity.current.account_id
          }
        }
      },
      {
        Sid    = "AllowEventBridge"
        Effect = "Allow"
        Principal = {
          Service = "events.amazonaws.com"
        }
        Action   = "SNS:Publish"
        Resource = aws_sns_topic.buildkite_alerts.arn
        Condition = {
          StringEquals = {
            "aws:SourceAccount" = data.aws_caller_identity.current.account_id
          }
          ArnLike = {
            "aws:SourceArn" = "arn:aws:events:${var.region}:${data.aws_caller_identity.current.account_id}:rule/buildkite-mockserver-*"
          }
        }
      }
    ]
  })
}

resource "aws_cloudwatch_metric_alarm" "asg_capacity_gap" {
  for_each = local.stacks

  alarm_name          = "buildkite-mockserver-${each.key}-capacity-gap"
  alarm_description   = "${each.key} queue: ASG desired capacity not met for 5 minutes (EC2 launch failures or Spot unavailability)"
  comparison_operator = "GreaterThanThreshold"
  evaluation_periods  = 5
  threshold           = 0
  datapoints_to_alarm = 3
  treat_missing_data  = "notBreaching"

  metric_query {
    id          = "desired"
    return_data = false

    metric {
      metric_name = "GroupDesiredCapacity"
      namespace   = "AWS/AutoScaling"
      period      = 60
      stat        = "Average"

      dimensions = {
        AutoScalingGroupName = each.value.asg_name
      }
    }
  }

  metric_query {
    id          = "actual"
    return_data = false

    metric {
      metric_name = "GroupInServiceInstances"
      namespace   = "AWS/AutoScaling"
      period      = 60
      stat        = "Average"

      dimensions = {
        AutoScalingGroupName = each.value.asg_name
      }
    }
  }

  metric_query {
    id          = "capacity_gap"
    expression  = "desired - actual"
    label       = "Capacity Gap"
    return_data = true
  }

  alarm_actions = [aws_sns_topic.buildkite_alerts.arn]
}

resource "aws_cloudwatch_metric_alarm" "scaler_lambda_errors" {
  for_each = local.stacks

  alarm_name          = "buildkite-mockserver-${each.key}-scaler-errors"
  alarm_description   = "${each.key} queue: Lambda scaler function is experiencing errors"
  comparison_operator = "GreaterThanThreshold"
  evaluation_periods  = 2
  threshold           = 0
  treat_missing_data  = "notBreaching"

  metric_name = "Errors"
  namespace   = "AWS/Lambda"
  period      = 300
  statistic   = "Sum"

  dimensions = {
    FunctionName = each.value.lambda_function_name
  }

  alarm_actions = [aws_sns_topic.buildkite_alerts.arn]
}

resource "aws_cloudwatch_metric_alarm" "scaler_lambda_not_invoked" {
  for_each = local.stacks

  alarm_name          = "buildkite-mockserver-${each.key}-scaler-not-invoked"
  alarm_description   = "${each.key} queue: Lambda scaler has not been invoked in ${each.value.not_invoked_period / 60} minutes (EventBridge schedule may be broken)"
  comparison_operator = "LessThanThreshold"
  evaluation_periods  = 1
  threshold           = 1
  treat_missing_data  = "breaching"

  metric_name = "Invocations"
  namespace   = "AWS/Lambda"
  period      = each.value.not_invoked_period
  statistic   = "Sum"

  dimensions = {
    FunctionName = each.value.lambda_function_name
  }

  alarm_actions = [aws_sns_topic.buildkite_alerts.arn]
}

resource "aws_cloudwatch_event_rule" "asg_launch_failures" {
  for_each = local.stacks

  name        = "buildkite-mockserver-${each.key}-asg-launch-failures"
  description = "Capture AutoScaling launch failures for ${each.key} queue"

  event_pattern = jsonencode({
    source      = ["aws.autoscaling"]
    detail-type = ["EC2 Instance Launch Unsuccessful"]
    detail = {
      AutoScalingGroupName = [each.value.asg_name]
    }
  })
}

resource "aws_cloudwatch_event_target" "asg_launch_failures_to_sns" {
  for_each = local.stacks

  rule      = aws_cloudwatch_event_rule.asg_launch_failures[each.key].name
  target_id = "SendToSNS"
  arn       = aws_sns_topic.buildkite_alerts.arn

  input_transformer {
    input_paths = {
      time    = "$.time"
      cause   = "$.detail.Cause"
      asgName = "$.detail.AutoScalingGroupName"
    }
    input_template = "\"Buildkite ${each.key} ASG Launch Failure at <time>: <cause> (ASG: <asgName>)\""
  }
}

# --- GuardDuty HIGH/CRITICAL findings -> SNS -----------------------------------
# EventBridge natively receives GuardDuty findings. Filter to severity >= 7
# (HIGH and CRITICAL) and forward to the existing alerts topic.
resource "aws_cloudwatch_event_rule" "guardduty_high_severity" {
  name        = "buildkite-mockserver-guardduty-high-severity"
  description = "Alert on GuardDuty findings with severity >= 7 (HIGH/CRITICAL)"

  event_pattern = jsonencode({
    source      = ["aws.guardduty"]
    detail-type = ["GuardDuty Finding"]
    detail = {
      severity = [{ numeric = [">=", 7] }]
    }
  })
}

resource "aws_cloudwatch_event_target" "guardduty_to_sns" {
  rule      = aws_cloudwatch_event_rule.guardduty_high_severity.name
  target_id = "SendToSNS"
  arn       = aws_sns_topic.buildkite_alerts.arn

  input_transformer {
    input_paths = {
      severity = "$.detail.severity"
      title    = "$.detail.title"
      account  = "$.detail.accountId"
      region   = "$.detail.region"
      type     = "$.detail.type"
    }
    input_template = "\"GuardDuty HIGH/CRITICAL Finding (severity <severity>): <title> [type=<type>, account=<account>, region=<region>]\""
  }
}

resource "aws_cloudwatch_dashboard" "buildkite_infrastructure" {
  dashboard_name = "buildkite-mockserver-infrastructure"

  dashboard_body = jsonencode({
    widgets = concat(
      [for key, stack in local.stacks : {
        type   = "metric"
        width  = 12
        height = 6
        properties = {
          title  = "${title(key)} Agent Capacity"
          region = var.region
          metrics = [
            ["AWS/AutoScaling", "GroupDesiredCapacity", "AutoScalingGroupName", stack.asg_name, { stat = "Average", label = "Desired" }],
            [".", "GroupInServiceInstances", ".", ".", { stat = "Average", label = "Running" }],
            [".", "GroupPendingInstances", ".", ".", { stat = "Average", label = "Pending" }]
          ]
          period = 60
          yAxis = {
            left = {
              min = 0
            }
          }
        }
      }],
      [for key, stack in local.stacks : {
        type   = "metric"
        width  = 12
        height = 6
        properties = {
          title  = "${title(key)} Lambda Scaler Health"
          region = var.region
          metrics = [
            ["AWS/Lambda", "Invocations", "FunctionName", stack.lambda_function_name, { stat = "Sum", label = "Invocations" }],
            [".", "Errors", ".", ".", { stat = "Sum", label = "Errors" }],
            [".", "Duration", ".", ".", { stat = "Average", label = "Duration (ms)" }]
          ]
          period = 60
          yAxis = {
            left = {
              min = 0
            }
          }
        }
      }],
      [for key, stack in local.stacks : {
        type   = "log"
        width  = 24
        height = 6
        properties = {
          title  = "${title(key)} Scaler Lambda Logs"
          region = var.region
          query  = "SOURCE '${stack.scaler_log_group}'\n| fields @timestamp, @message\n| sort @timestamp desc\n| limit 100"
        }
      }]
    )
  })
}
