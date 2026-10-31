INSERT INTO products (id, sku, name, description, category, brand, base_price, active)
VALUES
    ('11111111-1111-4111-8111-111111111111', 'SKU-NOTE-0001', 'Notebook Pro 14', 'Notebook 14 polegadas com 32GB de memoria', 'informatica', 'Nexora', 8999.90, TRUE),
    ('22222222-2222-4222-8222-222222222222', 'SKU-FONE-0002', 'Fone Bluetooth ANC', 'Fone over-ear com cancelamento ativo de ruido', 'audio', 'Sonvia', 1299.00, TRUE),
    ('33333333-3333-4333-8333-333333333333', 'SKU-CAFE-0003', 'Cafeteira Expresso 20 Bar', 'Cafeteira expresso com moedor integrado', 'cozinha', 'Brewly', 2499.50, TRUE),
    ('44444444-4444-4444-8444-444444444444', 'SKU-MON-0004', 'Monitor Ultrawide 34', 'Monitor ultrawide 34 polegadas 144Hz', 'informatica', 'Nexora', 4299.00, TRUE),
    ('55555555-5555-4555-8555-555555555555', 'SKU-TECL-0005', 'Teclado Mecanico Low Profile', 'Teclado mecanico sem fio switch marrom', 'informatica', 'Keyra', 899.90, TRUE)
ON CONFLICT (id) DO NOTHING;

INSERT INTO products (id, sku, name, description, category, brand, base_price, active)
VALUES
    ('99999999-9991-4991-8991-999999999991', 'SKU-SLOW-9001', 'Produto de teste lento', 'Downstream de preco responde acima do time limiter', 'laboratorio', 'ChaosLab', 199.90, TRUE),
    ('99999999-9992-4992-8992-999999999992', 'SKU-FAIL-9002', 'Produto de teste com falha', 'Downstream de preco responde 500 continuamente', 'laboratorio', 'ChaosLab', 299.90, TRUE),
    ('99999999-9993-4993-8993-999999999993', 'SKU-FLAKY-9003', 'Produto de teste instavel', 'Downstream falha nas duas primeiras tentativas e depois recupera', 'laboratorio', 'ChaosLab', 399.90, TRUE)
ON CONFLICT (id) DO NOTHING;
