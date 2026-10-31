CREATE TABLE IF NOT EXISTS products (
    id UUID PRIMARY KEY,
    sku VARCHAR(32) NOT NULL,
    name VARCHAR(160) NOT NULL,
    description VARCHAR(2000),
    category VARCHAR(80) NOT NULL,
    brand VARCHAR(80) NOT NULL,
    base_price NUMERIC(12, 2) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX IF NOT EXISTS ux_products_sku ON products (sku);
CREATE INDEX IF NOT EXISTS ix_products_active_created_at ON products (active, created_at DESC);
CREATE INDEX IF NOT EXISTS ix_products_category ON products (category);
