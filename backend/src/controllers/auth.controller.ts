import { Request, Response } from 'express';
import bcrypt from 'bcrypt';
import jwt from 'jsonwebtoken';
import { authDb } from '../db';
import crypto from 'crypto';
import { OAuth2Client } from 'google-auth-library';

const JWT_SECRET = process.env.JWT_SECRET || '';
const client = new OAuth2Client(process.env.GOOGLE_CLIENT_ID);

export const getGoogleConfig = (req: Request, res: Response) => {
    const clientId = process.env.GOOGLE_CLIENT_ID;

    if (!clientId) {
        res.json({ configured: false });
        return;
    }

    const frontendBase = process.env.FRONTEND_URL || 'http://localhost:3000';
    const redirectUri = process.env.FRONTEND_OAUTH_CALLBACK_URL || `${frontendBase}/auth/callback/google`;
    const scopes = 'https://www.googleapis.com/auth/userinfo.email https://www.googleapis.com/auth/userinfo.profile';
    const authUrl = `https://accounts.google.com/o/oauth2/v2/auth?client_id=${clientId}&redirect_uri=${encodeURIComponent(redirectUri)}&response_type=code&scope=${encodeURIComponent(scopes)}&access_type=offline&prompt=consent`;

    res.json({
        configured: true,
        clientId,
        authUrl,
        redirectUri
    });
};

export const refreshToken = (req: Request, res: Response): void => {
    const { refreshToken } = req.body;
    if (!refreshToken) { res.status(401).json({ message: 'Refresh token required ' }); return; }
    try {
        const decoded = jwt.verify(refreshToken, JWT_SECRET) as any;
        const accessToken = jwt.sign(
            { id: decoded.id, email: decoded.email, role: decoded.role, fullName: decoded.fullName },
            JWT_SECRET,
            { expiresIn: '7d' }
        );
        res.json({ accessToken, refreshToken: accessToken });
    } catch {
        res.status(401).json({ message: 'Invalid refresh token' });
    }
};

export const googleCallback = async (req: Request, res: Response): Promise<void> => {
    try {
        const frontendBase = process.env.FRONTEND_URL || 'http://localhost:3000';
        const redirectUri = process.env.FRONTEND_OAUTH_CALLBACK_URL || `${frontendBase}/auth/callback/google`;

        const { code } = req.query;
        if (!code || typeof code !== 'string') {
            res.redirect(`${frontendBase}/auth/login?error=NoCodeProvided`);
            return;
        }

        const oauth2Client = new OAuth2Client(process.env.GOOGLE_CLIENT_ID, process.env.GOOGLE_CLIENT_SECRET, redirectUri);

        const { tokens } = await oauth2Client.getToken(code);
        oauth2Client.setCredentials(tokens);

        const ticket = await oauth2Client.verifyIdToken({
            idToken: tokens.id_token!,
            audience: process.env.GOOGLE_CLIENT_ID,
        });

        const payload = ticket.getPayload();
        if (!payload || !payload.email) {
            res.redirect(`${frontendBase}/auth/login?error=InvalidGoogleToken`);
            return;
        }

        const email = payload.email;
        let user = await authDb.user.findUnique({ where: { email } });
        if (!user) {
            user = await authDb.user.create({
                data: {
                    email,
                    fullName: payload.name || payload.given_name || 'Google User',
                    password: crypto.randomUUID(),
                    role: 'CORPORATE'
                }
            });
        }

        const accessToken = jwt.sign(
            { id: user.id, email: user.email, role: user.role, fullName: user.fullName },
            JWT_SECRET,
            { expiresIn: '7d' }
        );

        // Redirect back to frontend with tokens
        res.redirect(`${frontendBase}/auth/callback?token=${accessToken}&refreshToken=${accessToken}`);
    } catch (error) {
        console.error("Google Auth Callback Error:", error);
        const frontendBase = process.env.FRONTEND_URL || 'http://localhost:3000';
        res.redirect(`${frontendBase}/auth/login?error=GoogleAuthFailed`);
    }
};

export const googleSignIn = async (req: Request, res: Response): Promise<void> => {
    try {
        const { code } = req.body;
        if (!code) {
            res.status(400).json({ message: 'Authorization code required' });
            return;
        }

        const frontendBase = process.env.FRONTEND_URL || 'http://localhost:3000';
        const redirectUri = process.env.FRONTEND_OAUTH_CALLBACK_URL || `${frontendBase}/auth/callback/google`;
        const oauth2Client = new OAuth2Client(process.env.GOOGLE_CLIENT_ID, process.env.GOOGLE_CLIENT_SECRET, redirectUri);

        const { tokens } = await oauth2Client.getToken(code);

        const ticket = await oauth2Client.verifyIdToken({
            idToken: tokens.id_token!,
            audience: process.env.GOOGLE_CLIENT_ID,
        });

        const payload = ticket.getPayload();
        if (!payload || !payload.email) {
            res.status(401).json({ message: 'Invalid Google token' });
            return;
        }

        const email = payload.email;
        let user = await authDb.user.findUnique({ where: { email } });
        if (!user) {
            user = await authDb.user.create({
                data: {
                    email,
                    fullName: payload.name || payload.given_name || 'Google User',
                    password: crypto.randomUUID(),
                    role: 'CORPORATE'
                }
            });
        }

        const accessToken = jwt.sign(
            { id: user.id, email: user.email, role: user.role, fullName: user.fullName },
            JWT_SECRET,
            { expiresIn: '7d' }
        );

        res.status(200).json({
            accessToken,
            refreshToken: accessToken,
            user: { id: user.id, email: user.email, fullName: user.fullName, role: user.role },
        });
    } catch (error) {
        console.error("Google Auth Error:", error);
        res.status(500).json({ message: 'Server error during Google Sign In' });
    }
};

export const register = async (req: Request, res: Response): Promise<void> => {
    try {
        const { email, password, fullName, name, role } = req.body;
        const actualName = fullName || name;

        const existingUser = await authDb.user.findUnique({ where: { email } });
        if (existingUser) {
            res.status(409).json({ message: 'Email is already registered' });
            return;
        }

        const hashedPassword = await bcrypt.hash(password, 10);
        const user = await authDb.user.create({
            data: {
                email,
                fullName: actualName,
                password: hashedPassword,
                role: role || 'CORPORATE',
            }
        });

        const accessToken = jwt.sign(
            { id: user.id, email: user.email, role: user.role, fullName: user.fullName },
            JWT_SECRET,
            { expiresIn: '7d' }
        );

        res.status(201).json({
            accessToken,
            refreshToken: accessToken,
            user: { id: user.id, email: user.email, fullName: user.fullName, name: user.fullName, role: user.role },
        });
    } catch (error) {
        res.status(500).json({ message: 'Server error during registration' });
    }
};

export const login = async (req: Request, res: Response): Promise<void> => {
    try {
        const { email, password } = req.body;

        const user = await authDb.user.findUnique({ where: { email } });
        if (!user) {
            res.status(401).json({ message: 'Invalid credentials' });
            return;
        }

        const isMatch = await bcrypt.compare(password, user.password);
        if (!isMatch) {
            res.status(401).json({ message: 'Invalid credentials' });
            return;
        }

        const accessToken = jwt.sign(
            { id: user.id, email: user.email, role: user.role, fullName: user.fullName },
            JWT_SECRET,
            { expiresIn: '7d' }
        );

        res.status(200).json({
            accessToken,
            refreshToken: accessToken,
            user: { id: user.id, email: user.email, fullName: user.fullName, role: user.role },
        });
    } catch (err) {
        console.error("LOGIN ERROR:", err);
        res.status(500).json({ message: 'Server error during login' });
    }
};

export const getMe = async (req: Request, res: Response): Promise<void> => {
    try {
        const userId = (req as any).user?.id;

        const user = await authDb.user.findUnique({ where: { id: userId } });
        if (!user) {
            res.status(404).json({ message: 'User not found' });
            return;
        }

        res.status(200).json({
            id: user.id,
            email: user.email,
            fullName: user.fullName,
            role: user.role,
        });
    } catch (error) {
        res.status(500).json({ message: 'Server error' });
    }
};
