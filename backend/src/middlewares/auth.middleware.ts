import { Request, Response, NextFunction } from 'express';
import jwt from 'jsonwebtoken';
import fs from 'fs';

const JWT_SECRET = process.env.JWT_SECRET || '';

export const authenticate = (req: Request, res: Response, next: NextFunction): void => {
    fs.appendFileSync('auth.log', `HIT: ${req.method} ${req.originalUrl}\n`);
    const token = req.headers.authorization?.split(' ')[1];

    if (!token) {
        console.log('REJECTED NO TOKEN');
        res.status(401).json({ message: 'No token provided' });
        return;
    }

    try {
        const decoded = jwt.verify(token, JWT_SECRET);
        (req as any).user = decoded;
        next();
    } catch (error) {
        res.status(401).json({ message: 'Invalid or expired token' });
        return;
    }
};

export const authorizeRole = (roles: string[]) => (req: Request, res: Response, next: NextFunction): void => {
    if (!roles.includes((req as any).user?.role)) {
        res.status(403).json({ message: 'Forbidden' });
        return;
    }
    next();
};
