INSERT INTO users(username,password_hash,role,active)
VALUES ('admin','{plain}admin123','REPORT_ADMIN',true)
ON CONFLICT (username) DO NOTHING;
