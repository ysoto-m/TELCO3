CREATE DATABASE telco_demo CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE telco_demo;

CREATE TABLE usuarios (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    username VARCHAR(100) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    rol ENUM('ADMIN','AGENTE','BACKOFFICE','SUPERVISOR') NOT NULL,
    supervisor_id BIGINT,
    activo BOOLEAN DEFAULT TRUE,
    created_at DATETIME,
    updated_at DATETIME
);

CREATE TABLE ventas (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    agente_id BIGINT,
    dni_cliente VARCHAR(20),
    nombre_cliente VARCHAR(200),
    telefono_cliente VARCHAR(50),
    direccion_cliente VARCHAR(300),
    plan_actual VARCHAR(100),
    plan_nuevo VARCHAR(100),
    codigo_llamada VARCHAR(200) UNIQUE,
    producto VARCHAR(100),
    monto DECIMAL(12,2),
    estado ENUM('PENDIENTE','APROBADA','RECHAZADA') DEFAULT 'PENDIENTE',
    motivo_rechazo VARCHAR(500),
    fecha_registro DATETIME,
    fecha_validacion DATETIME,
    created_at DATETIME,
    updated_at DATETIME,
    FOREIGN KEY (agente_id) REFERENCES usuarios(id)
);

-- seed users (password = "password", bcrypt)
INSERT INTO usuarios (username, password_hash, rol, supervisor_id, activo, created_at, updated_at) VALUES
('admin', '$2a$10$7qDxK0PZ9r7P7cP0r2oTReB0wG1xJZqKTeWYkljmkQG1yXnRV3C7K', 'ADMIN', NULL, TRUE, NOW(), NOW()),
('agente1','$2a$10$7qDxK0PZ9r7P7cP0r2oTReB0wG1xJZqKTeWYkljmkQG1yXnRV3C7K', 'AGENTE', 4, TRUE, NOW(), NOW()),
('back1', '$2a$10$7qDxK0PZ9r7P7cP0r2oTReB0wG1xJZqKTeWYkljmkQG1yXnRV3C7K', 'BACKOFFICE', NULL, TRUE, NOW(), NOW()),
('sup1',  '$2a$10$7qDxK0PZ9r7P7cP0r2oTReB0wG1xJZqKTeWYkljmkQG1yXnRV3C7K', 'SUPERVISOR', NULL, TRUE, NOW(), NOW());
