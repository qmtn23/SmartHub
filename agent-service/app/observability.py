from opentelemetry import metrics


meter = metrics.get_meter("smarthub.agent_service")
routes = meter.create_counter("smarthub.agent.routes")
agent_activations = meter.create_counter("smarthub.agent.activations")
low_confidence_routes = meter.create_counter("smarthub.agent.router.low_confidence")
handoffs = meter.create_counter("smarthub.agent.handoffs")
handoff_blocks = meter.create_counter("smarthub.agent.handoff.blocks")
tool_calls = meter.create_counter("smarthub.agent.tool.calls")
tool_failures = meter.create_counter("smarthub.agent.tool.failures")
model_calls = meter.create_counter("smarthub.agent.model.calls")
tokens = meter.create_counter("smarthub.agent.tokens")
run_latency = meter.create_histogram("smarthub.agent.run.duration", unit="s")
errors = meter.create_counter("smarthub.agent.errors")
